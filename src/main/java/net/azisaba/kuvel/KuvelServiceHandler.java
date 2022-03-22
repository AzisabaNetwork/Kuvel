package net.azisaba.kuvel;

import com.velocitypowered.api.proxy.server.ServerInfo;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.discovery.LoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.ServerDiscovery;
import net.azisaba.kuvel.loadbalancer.LoadBalancer;
import net.azisaba.kuvel.util.LabelKeys;
import net.azisaba.kuvel.util.UidAndServerNameMap;

@Getter
@RequiredArgsConstructor
public class KuvelServiceHandler {

  private final Kuvel plugin;
  private final KubernetesClient client;
  private final HashMap<String, LoadBalancer> loadBalancerServerMap = new HashMap<>();

  private final UidAndServerNameMap podUidAndServerNameMap = new UidAndServerNameMap();
  private final UidAndServerNameMap replicaSetUidAndServerNameMap = new UidAndServerNameMap();

  private ServerDiscovery serverDiscovery;
  private LoadBalancerDiscovery loadBalancerDiscovery;

  public void registerLoadBalancer(LoadBalancer loadBalancer) {
    loadBalancerServerMap.put(loadBalancer.getServer().getServerInfo().getName(), loadBalancer);
    replicaSetUidAndServerNameMap.register(
        loadBalancer.getReplicaSetUid(), loadBalancer.getServer().getServerInfo().getName());
    updateLoadBalancerEndpoints(loadBalancer);

    String serverName = loadBalancer.getServer().getServerInfo().getName();
    plugin
        .getLogger()
        .info(
            "Registered load balancer: "
                + serverName
                + " ("
                + loadBalancer.getReplicaSetUid()
                + ")");
  }

  public void unregisterLoadBalancer(String replicaSetUid) {
    String serverName = replicaSetUidAndServerNameMap.getServerNameFromUid(replicaSetUid);
    if (serverName == null) {
      return;
    }
    LoadBalancer loadBalancer = loadBalancerServerMap.get(serverName);
    if (loadBalancer != null) {
      unregisterLoadBalancer(loadBalancer);
    }
  }

  public void unregisterLoadBalancer(LoadBalancer loadBalancer) {
    String serverName = loadBalancer.getServer().getServerInfo().getName();
    plugin
        .getProxy()
        .getServer(serverName)
        .ifPresent(server -> plugin.getProxy().unregisterServer(server.getServerInfo()));
    loadBalancerServerMap.remove(serverName);
    replicaSetUidAndServerNameMap.unregister(loadBalancer.getReplicaSetUid());

    plugin
        .getLogger()
        .info(
            "Unregistered load balancer: "
                + serverName
                + " ("
                + loadBalancer.getReplicaSetUid()
                + ")");
  }

  public Optional<LoadBalancer> getLoadBalancer(String serverName) {
    return Optional.ofNullable(loadBalancerServerMap.get(serverName));
  }

  private void updateLoadBalancerEndpoints(LoadBalancer loadBalancer) {
    List<Pod> pods =
        client
            .pods()
            .inAnyNamespace()
            .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
            .list()
            .getItems();

    List<String> endpoints = new ArrayList<>();
    for (Pod pod : pods) {
      if (pod.hasOwnerReferenceFor(loadBalancer.getReplicaSetUid())) {
        String serverName = podUidAndServerNameMap.getServerNameFromUid(pod.getMetadata().getUid());
        if (serverName != null) {
          endpoints.add(serverName);
        }
      }
    }
    loadBalancer.setEndpoints(endpoints);
  }

  public void setAndRunServerDiscovery(@Nullable ServerDiscovery newServerDiscovery) {
    if (serverDiscovery != null) {
      serverDiscovery.shutdown();
    }

    serverDiscovery = newServerDiscovery;
    if (serverDiscovery != null) {
      HashMap<String, Pod> servers = serverDiscovery.getServersForStartup();
      for (Entry<String, Pod> entry : servers.entrySet()) {
        Pod pod = entry.getValue();
        InetSocketAddress address = new InetSocketAddress(pod.getStatus().getPodIP(), 25565);
        plugin.getProxy().registerServer(new ServerInfo(entry.getKey(), address));

        for (LoadBalancer loadBalancer : loadBalancerServerMap.values()) {
          if (pod.hasOwnerReferenceFor(loadBalancer.getReplicaSetUid())) {
            loadBalancer.addEndpoint(entry.getKey());
          }
        }
      }
      serverDiscovery.start();
    }
  }

  public void setAndRunLoadBalancerDiscovery(
      @Nullable LoadBalancerDiscovery newLoadBalancerDiscovery) {
    if (loadBalancerDiscovery != null) {
      loadBalancerDiscovery.shutdown();
    }

    loadBalancerDiscovery = newLoadBalancerDiscovery;
    if (loadBalancerDiscovery != null) {
      loadBalancerDiscovery.registerLoadBalancersForStartup();
      loadBalancerDiscovery.start();
    }
  }

  public void shutdown() {
    if (serverDiscovery != null) {
      serverDiscovery.shutdown();
    }
    if (loadBalancerDiscovery != null) {
      loadBalancerDiscovery.shutdown();
    }
  }

  public void registerPod(Pod pod, String serverName) {
    InetSocketAddress address = new InetSocketAddress(pod.getStatus().getPodIP(), 25565);
    plugin.getProxy().registerServer(new ServerInfo(serverName, address));
    podUidAndServerNameMap.register(pod.getMetadata().getUid(), serverName);

    for (LoadBalancer loadBalancer : loadBalancerServerMap.values()) {
      if (pod.hasOwnerReferenceFor(loadBalancer.getReplicaSetUid())) {
        loadBalancer.addEndpoint(serverName);
      }
    }

    plugin
        .getLogger()
        .info("Registered server: " + serverName + " (" + pod.getMetadata().getUid() + ")");
  }

  public void registerPod(String podUid, String serverName) {
    Optional<Pod> pod =
        client
            .pods()
            .inAnyNamespace()
            .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
            .list()
            .getItems()
            .stream()
            .filter(p -> p.getMetadata().getUid().equals(podUid))
            .findFirst();

    if (!pod.isPresent()) {
      return;
    }

    registerPod(pod.get(), serverName);
  }

  public void unregisterPod(String podUid) {
    if (podUidAndServerNameMap.getServerNameFromUid(podUid) == null) {
      return;
    }

    String serverName = podUidAndServerNameMap.unregister(podUid);
    plugin
        .getProxy()
        .getServer(serverName)
        .ifPresent(server -> plugin.getProxy().unregisterServer(server.getServerInfo()));

    for (LoadBalancer loadBalancer : loadBalancerServerMap.values()) {
      if (loadBalancer.getEndpointServers().contains(serverName)) {
        loadBalancer.removeEndpoint(serverName);
      }
    }

    plugin.getLogger().info("Unregistered server: " + serverName + " (" + podUid + ")");
  }

  public void unregisterPod(Pod pod) {
    unregisterPod(pod.getMetadata().getUid());
  }

  public boolean isPodRegistered(String podId) {
    return podUidAndServerNameMap.getServerNameFromUid(podId) != null;
  }
}
