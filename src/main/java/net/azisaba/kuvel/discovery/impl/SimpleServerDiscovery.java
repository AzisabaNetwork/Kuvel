package net.azisaba.kuvel.discovery.impl;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.ServerDiscovery;
import net.azisaba.kuvel.util.LabelKeys;

@RequiredArgsConstructor
public class SimpleServerDiscovery implements ServerDiscovery {

  private final KubernetesClient client;
  private final Kuvel plugin;
  private final KuvelServiceHandler kuvelServiceHandler;

  private final ExecutorService serverDiscoveryExecutor = Executors.newFixedThreadPool(1);

  private final ReentrantLock lock = new ReentrantLock();

  @Override
  public void start() {
    run(
        serverDiscoveryExecutor,
        () ->
            client
                .pods()
                .inAnyNamespace()
                .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
                .withField("status.phase", "Running")
                .watch(
                    new Watcher<Pod>() {
                      @Override
                      public void eventReceived(Action action, Pod pod) {
                        lock.lock();
                        try {
                          if (action == Action.ADDED) {
                            registerPodOrIgnore(pod);
                          } else if (action == Action.DELETED) {
                            unregisterPodOrIgnore(pod);
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
    serverDiscoveryExecutor.shutdownNow();
  }

  @Override
  public HashMap<String, Pod> getServersForStartup() {
    HashMap<String, Pod> servers = new HashMap<>();
    client
        .pods()
        .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
        .list()
        .getItems()
        .forEach(
            pod -> {
              String uid = pod.getMetadata().getUid();
              String serverName =
                  pod.getMetadata()
                      .getLabels()
                      .getOrDefault(LabelKeys.SERVER_NAME.getKey(), pod.getMetadata().getUid());

              if (servers.containsKey(serverName)
                  || plugin.getProxy().getServer(serverName).isPresent()) {
                serverName += "-1";
                int i = 1;
                while (servers.containsKey(serverName)
                    && !plugin.getProxy().getServer(serverName).isPresent()) {
                  serverName =
                      serverName.substring(
                              0, serverName.length() - (1 + String.valueOf(i).length()))
                          + "-"
                          + (i + 1);
                  i++;
                }
              }
              servers.put(serverName, pod);
              kuvelServiceHandler.getPodUidAndServerNameMap().register(uid, serverName);

              plugin.getLogger().info("Found server: " + serverName + " (" + uid + ")");
            });

    return servers;
  }

  private void registerPodOrIgnore(Pod pod) {
    String uid = pod.getMetadata().getUid();
    if (kuvelServiceHandler.getPodUidAndServerNameMap().getServerNameFromUid(uid) != null) {
      return;
    }

    String serverName =
        getValidServerName(
            pod.getMetadata()
                .getLabels()
                .getOrDefault(LabelKeys.SERVER_NAME.getKey(), pod.getMetadata().getName()));

    kuvelServiceHandler.getPodUidAndServerNameMap().register(uid, serverName);
    kuvelServiceHandler.registerPod(pod, serverName);
  }

  private void unregisterPodOrIgnore(Pod pod) {
    kuvelServiceHandler.unregisterPod(pod);
    // podUidAndServerNameMap.unregister(uid); // no need
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

  private String getValidServerName(String prefer) {
    if (!plugin.getProxy().getServer(prefer).isPresent()) {
      return prefer;
    }

    String name = prefer + "-1";
    int i = 1;
    while (plugin.getProxy().getServer(name).isPresent()) {
      name = name.substring(0, name.length() - (1 + String.valueOf(i).length())) + "-" + (i + 1);
      i++;
    }
    return name;
  }
}
