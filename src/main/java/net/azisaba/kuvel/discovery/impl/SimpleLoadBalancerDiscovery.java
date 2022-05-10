package net.azisaba.kuvel.discovery.impl;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.LoadBalancerDiscovery;
import net.azisaba.kuvel.loadbalancer.LoadBalancer;
import net.azisaba.kuvel.loadbalancer.strategy.impl.RoundRobinLoadBalancingStrategy;
import net.azisaba.kuvel.util.LabelKeys;

@RequiredArgsConstructor
public class SimpleLoadBalancerDiscovery implements LoadBalancerDiscovery {

  private final KubernetesClient client;
  private final Kuvel plugin;
  private final KuvelServiceHandler kuvelServiceHandler;

  private final ExecutorService loadBalancerDiscoveryExecutor = Executors.newFixedThreadPool(1);
  private final ReentrantLock lock = new ReentrantLock();

  @Override
  public void start() {
    run(
        loadBalancerDiscoveryExecutor,
        () ->
            client
                .apps()
                .replicaSets()
                .inAnyNamespace()
                .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
                .withLabel(LabelKeys.SERVER_NAME.getKey())
                .watch(
                    new Watcher<>() {
                      @Override
                      public void eventReceived(Action action, ReplicaSet replicaSet) {
                        lock.lock();
                        try {
                          if (replicaSet.getStatus().getReplicas() <= 0
                              && replicaSet.getStatus().getObservedGeneration() != null
                              && replicaSet.getStatus().getObservedGeneration() > 1) {
                            unregisterOrIgnore(replicaSet);
                            return;
                          }

                          if (action == Action.ADDED) {
                            registerOrIgnore(replicaSet);
                          } else if (action == Action.DELETED) {
                            unregisterOrIgnore(replicaSet);
                          }
                        } finally {
                          lock.unlock();
                        }
                      }

                      @Override
                      public void onClose(WatcherException e) {}
                    }));
  }

  @Override
  public void shutdown() {
    loadBalancerDiscoveryExecutor.shutdownNow();
  }

  @Override
  public void registerLoadBalancersForStartup() {
    client
        .apps()
        .replicaSets()
        .inAnyNamespace()
        .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
        .withLabel(LabelKeys.SERVER_NAME.getKey())
        .list()
        .getItems()
        .stream()
        .filter(
            replicaSet ->
                replicaSet.getStatus().getReplicas() > 0
                    || replicaSet.getStatus().getObservedGeneration() == null
                    || replicaSet.getStatus().getObservedGeneration() <= 1)
        .forEach(this::registerOrIgnore);
  }

  private void registerOrIgnore(ReplicaSet replicaSet) {
    String uid = replicaSet.getMetadata().getUid();
    if (kuvelServiceHandler.getReplicaSetUidAndServerNameMap().getServerNameFromUid(uid) != null) {
      return;
    }

    String serverName = replicaSet.getMetadata().getLabels().get(LabelKeys.SERVER_NAME.getKey());

    if (plugin.getProxy().getServer(serverName).isPresent()) {
      plugin
          .getLogger()
          .info("Failed to add load balancer. Server name already occupied: " + serverName);
      return;
    }
    RegisteredServer server =
        plugin
            .getProxy()
            .registerServer(new ServerInfo(serverName, new InetSocketAddress("0.0.0.0", 0)));

    LoadBalancer loadBalancer =
        new LoadBalancer(plugin.getProxy(), server, new RoundRobinLoadBalancingStrategy(), uid);
    kuvelServiceHandler.registerLoadBalancer(loadBalancer);
  }

  public void unregisterOrIgnore(ReplicaSet replicaSet) {
    String uid = replicaSet.getMetadata().getUid();
    if (kuvelServiceHandler.getReplicaSetUidAndServerNameMap().getServerNameFromUid(uid) == null) {
      return;
    }

    kuvelServiceHandler.unregisterLoadBalancer(uid);
  }

  private void run(ExecutorService service, Runnable runnable) {
    service.submit(runnable);
    for (int i = 0; i < 1000; i++) {
      service.submit(
          () -> {
            try {
              Thread.sleep(3000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }

            runnable.run();
          });
    }
  }
}
