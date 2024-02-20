package net.azisaba.kuvel;

import com.velocitypowered.api.proxy.server.ServerInfo;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

  private final List<String> initialServerNames = new ArrayList<>();

  private final AtomicReference<ServerDiscovery> serverDiscovery = new AtomicReference<>();
  private final AtomicReference<LoadBalancerDiscovery> loadBalancerDiscovery =
      new AtomicReference<>();

  /**
   * Registers a load balancer server to the map.
   *
   * @param loadBalancer The load balancer to register.
   */
  public void registerLoadBalancer(LoadBalancer loadBalancer) {
    String serverName = loadBalancer.getServer().getServerInfo().getName();
    loadBalancerServerMap.put(serverName, loadBalancer);
    replicaSetUidAndServerNameMap.register(loadBalancer.getReplicaSetUid(), serverName);

    updateLoadBalancerEndpoints(loadBalancer);

    if (loadBalancer.isInitialServer() && !initialServerNames.contains(serverName)) {
      initialServerNames.add(serverName);
    }

    plugin
        .getLogger()
        .info(
            "Registered load balancer: "
                + serverName
                + " ("
                + loadBalancer.getReplicaSetUid()
                + ")");
  }

  /**
   * Unregisters a load balancer server from the map.
   *
   * @param replicaSetUid The ReplicaSet UID of the load balancer to unregister.
   */
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

  /**
   * Unregisters a load balancer server from the Velocity server.
   *
   * @param loadBalancer The load balancer to unregister.
   */
  public void unregisterLoadBalancer(LoadBalancer loadBalancer) {
    String serverName = loadBalancer.getServer().getServerInfo().getName();
    plugin
        .getProxy()
        .getServer(serverName)
        .ifPresent(server -> plugin.getProxy().unregisterServer(server.getServerInfo()));
    loadBalancerServerMap.remove(serverName);
    replicaSetUidAndServerNameMap.unregister(loadBalancer.getReplicaSetUid());

    initialServerNames.remove(serverName);

    plugin
        .getLogger()
        .info(
            "Unregistered load balancer: "
                + serverName
                + " ("
                + loadBalancer.getReplicaSetUid()
                + ")");
  }

  /**
   * Get a registered load balancer instance.
   *
   * @param serverName The name of the load balancer server.
   * @return The load balancer instance.
   */
  public Optional<LoadBalancer> getLoadBalancer(String serverName) {
    return Optional.ofNullable(loadBalancerServerMap.get(serverName));
  }

  /**
   * Update endpoints of a load balancer.
   *
   * @param loadBalancer The load balancer to update.
   */
  private void updateLoadBalancerEndpoints(LoadBalancer loadBalancer) {
    // TODO: This may be replaced by more improved function
    FilterWatchListDeletable<Pod, PodList, PodResource> request = client.pods()
        .inAnyNamespace();

    for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
      request = request.withLabel(e.getKey(), e.getValue());
    }

    List<Pod> pods = request.list().getItems();

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

  /**
   * Replace new server discovery instance and unregister old one. Specify null for shutdown current
   * discovery instance.
   *
   * @param newServerDiscovery The new server discovery instance. Specify null for shutdown current
   *     one.
   */
  public void setAndRunServerDiscovery(@Nullable ServerDiscovery newServerDiscovery) {
    if (newServerDiscovery != null) {
      newServerDiscovery.getServersForStartup()
          .forEach((serverName, pod) -> {
            boolean success = registerPod(pod, serverName);
            if (!success) {
              plugin.getProxy().getServer(entry.getKey()).ifPresent(server -> plugin.getProxy().unregisterServer(server.getServerInfo()));
        plugin.getLogger().warning("Failed to register pod. ( "
                  + "serverName = " + serverName + ", "
                  + "pod = " + pod.getMetadata().getUid()
                  + " )");
            }
          });

      newServerDiscovery.start();
    }

    serverDiscovery.getAndUpdate(
        (oldInstance) -> {
          if (oldInstance != null) {
            oldInstance.shutdown();
          }

          return newServerDiscovery;
        });
  }

  /**
   * Replace new load balancer discovery instance and unregister old one. Specify null for shutdown
   * current discovery instance.
   *
   * @param newInstance The new load balancer discovery instance. Specify null for shutdown current
   *     one.
   */
  public void setAndRunLoadBalancerDiscovery(@Nullable LoadBalancerDiscovery newInstance) {
    if (newInstance != null) {
      newInstance.registerLoadBalancersForStartup();
      newInstance.start();
    }

    loadBalancerDiscovery.getAndUpdate(
        oldInstance -> {
          if (oldInstance != null) {
            oldInstance.shutdown();
          }
          return newInstance;
        });
  }

  /** Shutdown all discovery instances. */
  public void shutdown() {
    setAndRunServerDiscovery(null);
    setAndRunLoadBalancerDiscovery(null);
  }

  /**
   * Register a pod for the specified server name.
   *
   * @param pod        The pod to register.
   * @param serverName The name of the server.
   * @return true if the pod is registered successfully.
   */
  public boolean registerPod(Pod pod, String serverName) {
    String ip = pod.getStatus().getPodIP();
    if (ip == null) {
      return false;
    }

    int port = 25565;
    Optional<ContainerPort> containerPort = pod.getSpec().getContainers().stream()
        .map(Container::getPorts)
        .flatMap(List::stream)
        .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase("minecraft"))
        .findFirst();

    if (containerPort.isPresent()) {
      port = containerPort.get().getContainerPort();
    }

    InetSocketAddress address = new InetSocketAddress(ip, port);
    plugin.getProxy().registerServer(new ServerInfo(serverName, address));
    podUidAndServerNameMap.register(pod.getMetadata().getUid(), serverName);

    for (LoadBalancer loadBalancer : loadBalancerServerMap.values()) {
      if (pod.hasOwnerReferenceFor(loadBalancer.getReplicaSetUid())) {
        loadBalancer.addEndpoint(serverName);
      }
    }

    String initialServerStr =
        pod.getMetadata().getLabels().getOrDefault(LabelKeys.INITIAL_SERVER.getKey(), "false");
    if (Boolean.parseBoolean(initialServerStr)) {
      initialServerNames.add(serverName);
    }

    plugin
        .getLogger()
        .info("Registered server: " + serverName + " (" + pod.getMetadata().getUid() + ")");
    return true;
  }

  /**
   * Unregister a pod with pod uid from the specified server name.
   *
   * @param podUid The pod uid to register.
   * @param serverName The name of the server.
   */
  public void registerPod(String podUid, String serverName) {
    FilterWatchListDeletable<Pod, PodList, PodResource> request = client.pods()
        .inAnyNamespace();

    for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
      request = request.withLabel(e.getKey(), e.getValue());
    }

    Optional<Pod> pod = request
        .list()
        .getItems()
        .stream()
        .filter(p -> p.getMetadata().getUid().equals(podUid))
        .findFirst();

    pod.ifPresent(p -> registerPod(p, serverName));
  }

  /**
   * Unregister a pod with the pod uid.
   *
   * @param podUid The pod uid to unregister.
   */
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

    initialServerNames.remove(serverName);

    plugin.getLogger().info("Unregistered server: " + serverName + " (" + podUid + ")");
  }

  /**
   * Unregister a pod.
   *
   * @param pod The pod to unregister.
   */
  public void unregisterPod(Pod pod) {
    unregisterPod(pod.getMetadata().getUid());
  }

  /**
   * Gets whether the specified server name is registered.
   *
   * @param podId The pod id to check.
   * @return true if the specified server name is registered.
   */
  public boolean isPodRegistered(String podId) {
    return podUidAndServerNameMap.getServerNameFromUid(podId) != null;
  }
}
