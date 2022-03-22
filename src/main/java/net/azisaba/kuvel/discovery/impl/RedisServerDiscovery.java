package net.azisaba.kuvel.discovery.impl;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.ServerDiscovery;
import net.azisaba.kuvel.redis.RedisConnectionLeader;
import net.azisaba.kuvel.redis.RedisKeys;
import net.azisaba.kuvel.util.LabelKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@RequiredArgsConstructor
public class RedisServerDiscovery implements ServerDiscovery {

  private final KubernetesClient client;
  private final Kuvel plugin;
  private final JedisPool jedisPool;
  private final String groupName;
  private final RedisConnectionLeader redisConnectionLeader;
  private final KuvelServiceHandler kuvelServiceHandler;

  private final ExecutorService serverDiscoveryExecutor = Executors.newFixedThreadPool(1);
  private final ReentrantLock lock = new ReentrantLock();

  @Override
  public void start() {
    if (!redisConnectionLeader.isLeader()) {
      return;
    }

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
    Map<String, String> podIdToServerNameMap;
    if (redisConnectionLeader.isLeader()) {
      try (Jedis jedis = jedisPool.getResource()) {
        podIdToServerNameMap = new HashMap<>(jedis.hgetAll(RedisKeys.SERVERS_PREFIX + groupName));

        for (String podUid : new ArrayList<>(podIdToServerNameMap.keySet())) {
          if (getPodUsingPodUid(podUid) == null) {
            podIdToServerNameMap.remove(podUid);
            jedis.hdel(RedisKeys.SERVERS_PREFIX + groupName, podUid);
            redisConnectionLeader.publishDeletedServer(podUid);
          }
        }

        Map<String, String> loadBalancerMap =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);

        client
            .pods()
            .inAnyNamespace()
            .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
            .withField("status.phase", "Running")
            .list()
            .getItems()
            .forEach(
                pod -> {
                  String uid = pod.getMetadata().getUid();
                  if (podIdToServerNameMap.containsKey(uid)) {
                    return;
                  }

                  String preferServerName =
                      pod.getMetadata()
                          .getLabels()
                          .getOrDefault(
                              LabelKeys.SERVER_NAME.getKey(), pod.getMetadata().getName());
                  String serverName =
                      getValidServerName(
                          preferServerName,
                          (name) ->
                              !podIdToServerNameMap.containsValue(name)
                                  && !loadBalancerMap.containsValue(name)
                                  && !plugin.getProxy().getServer(name).isPresent());

                  podIdToServerNameMap.put(uid, serverName);
                  jedis.hset(RedisKeys.SERVERS_PREFIX + groupName, uid, serverName);
                });
      }
    } else {
      try (Jedis jedis = jedisPool.getResource()) {
        podIdToServerNameMap = jedis.hgetAll(RedisKeys.SERVERS_PREFIX + groupName);
      }
    }

    String verb = "Fetched";
    if (redisConnectionLeader.isLeader()) {
      verb = "Found";
    }

    for (String podUid : podIdToServerNameMap.keySet()) {
      plugin
          .getLogger()
          .info(verb + " server: " + podIdToServerNameMap.get(podUid) + " (" + podUid + ")");
    }

    HashMap<String, Pod> servers = new HashMap<>();
    for (Entry<String, String> entry : podIdToServerNameMap.entrySet()) {
      Pod pod = getPodUsingPodUid(entry.getKey());
      if (pod == null) {
        continue;
      }

      servers.put(entry.getValue(), pod);
      kuvelServiceHandler.getPodUidAndServerNameMap().register(entry.getKey(), entry.getValue());
    }
    return servers;
  }

  private void registerPodOrIgnore(Pod pod) {
    String uid = pod.getMetadata().getUid();
    if (kuvelServiceHandler.getPodUidAndServerNameMap().getServerNameFromUid(uid) != null) {
      return;
    }

    String serverName;

    try (Jedis jedis = jedisPool.getResource()) {
      Map<String, String> serverMap = jedis.hgetAll(RedisKeys.SERVERS_PREFIX.getKey() + groupName);
      Map<String, String> loadBalancerMap =
          jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);

      String preferServerName =
          pod.getMetadata()
              .getLabels()
              .getOrDefault(LabelKeys.SERVER_NAME.getKey(), pod.getMetadata().getName());

      serverName =
          getValidServerName(
              preferServerName,
              (name) ->
                  !serverMap.containsValue(name)
                      && !loadBalancerMap.containsValue(name)
                      && !plugin.getProxy().getServer(name).isPresent());

      kuvelServiceHandler.getPodUidAndServerNameMap().register(uid, serverName);
      jedis.hset(RedisKeys.SERVERS_PREFIX.getKey() + groupName, uid, serverName);

      redisConnectionLeader.publishNewServer(uid, serverName);
      kuvelServiceHandler.registerPod(pod, serverName);
    }
  }

  private void unregisterPodOrIgnore(Pod pod) {
    String uid = pod.getMetadata().getUid();
    if (kuvelServiceHandler.getPodUidAndServerNameMap().getServerNameFromUid(uid) == null) {
      return;
    }

    kuvelServiceHandler.unregisterPod(uid);
    // podUidAndServerNameMap.unregister(uid); // no need
    redisConnectionLeader.publishDeletedServer(pod.getMetadata().getUid());

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.hdel(RedisKeys.SERVERS_PREFIX.getKey() + groupName, uid);
    }
  }

  private Pod getPodUsingPodUid(String podUid) {
    return client.pods().list().getItems().stream()
        .filter(pod -> pod.getMetadata().getUid().equals(podUid))
        .findFirst()
        .orElse(null);
  }

  private String getValidServerName(String prefer, Function<String, Boolean> isValid) {
    if (isValid.apply(prefer)) {
      return prefer;
    }

    String name = prefer + "-1";
    int i = 1;
    while (!isValid.apply(name)) {
      name = name.substring(0, name.length() - (1 + String.valueOf(i).length())) + "-" + (i + 1);
      i++;
    }
    return name;
  }

  private String getValidServerNameComparingRegisteredServers(String prefer) {
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
