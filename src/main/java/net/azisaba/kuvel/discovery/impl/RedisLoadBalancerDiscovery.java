package net.azisaba.kuvel.discovery.impl;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.LoadBalancerDiscovery;
import net.azisaba.kuvel.loadbalancer.LoadBalancer;
import net.azisaba.kuvel.loadbalancer.strategy.impl.RoundRobinLoadBalancingStrategy;
import net.azisaba.kuvel.redis.RedisConnectionLeader;
import net.azisaba.kuvel.redis.RedisKeys;
import net.azisaba.kuvel.util.LabelKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@RequiredArgsConstructor
public class RedisLoadBalancerDiscovery implements LoadBalancerDiscovery {

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

  private void registerOrIgnore(ReplicaSet replicaSet) {
    registerOrIgnore(replicaSet, false);
  }

  private void registerOrIgnore(ReplicaSet replicaSet, boolean isFetchedFromRedis) {
    String uid = replicaSet.getMetadata().getUid();
    if (kuvelServiceHandler.getReplicaSetUidAndServerNameMap().getServerNameFromUid(uid) != null) {
      return;
    }

    String serverName =
        replicaSet.getMetadata().getLabels().getOrDefault(LabelKeys.SERVER_NAME.getKey(), null);
    if (serverName == null) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      if (!isFetchedFromRedis) {
        Collection<String> loadBalancerNames =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName).values();

        if (loadBalancerNames.contains(serverName)
            || plugin.getProxy().getServer(serverName).isPresent()) {
          plugin
              .getLogger()
              .info("Failed to add load balancer. Server name already occupied: " + serverName);
          return;
        }
      }

      kuvelServiceHandler.getReplicaSetUidAndServerNameMap().register(uid, serverName);
      jedis.hset(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName, uid, serverName);

      redisConnectionLeader.publishNewLoadBalancer(uid, serverName);

      RegisteredServer server =
          plugin
              .getProxy()
              .registerServer(new ServerInfo(serverName, new InetSocketAddress("0.0.0.0", 0)));

      kuvelServiceHandler.registerLoadBalancer(
          new LoadBalancer(plugin.getProxy(), server, new RoundRobinLoadBalancingStrategy(), uid));
    }
  }

  private void unregisterOrIgnore(ReplicaSet replicaSet) {
    String uid = replicaSet.getMetadata().getUid();
    if (kuvelServiceHandler.getReplicaSetUidAndServerNameMap().getServerNameFromUid(uid) == null) {
      return;
    }

    kuvelServiceHandler.unregisterLoadBalancer(uid);
    // podUidAndServerNameMap.unregister(uid); // no need
    redisConnectionLeader.publishDeletedLoadBalancer(replicaSet.getMetadata().getUid());

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.hdel(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName, uid);
    }
  }

  @Override
  public void shutdown() {
    serverDiscoveryExecutor.shutdownNow();
  }

  @Override
  public void registerLoadBalancersForStartup() {
    if (redisConnectionLeader.isLeader()) {
      try (Jedis jedis = jedisPool.getResource()) {
        Map<String, String> uidAndServerNameMapInRedis =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);
        for (Map.Entry<String, String> entry : uidAndServerNameMapInRedis.entrySet()) {
          ReplicaSet replicaSet = getReplicaSetFromUid(entry.getKey());
          if (replicaSet == null) {
            jedis.hdel(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName, entry.getKey());
            continue;
          }
          registerOrIgnore(replicaSet, true);
        }

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
            .filter(
                replicaSet ->
                    !uidAndServerNameMapInRedis.containsKey(replicaSet.getMetadata().getUid()))
            .forEach(this::registerOrIgnore);
      }
    } else {
      try (Jedis jedis = jedisPool.getResource()) {
        Map<String, String> uidAndServerNameMapInRedis =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);
        for (Map.Entry<String, String> entry : uidAndServerNameMapInRedis.entrySet()) {
          ReplicaSet replicaSet = getReplicaSetFromUid(entry.getKey());
          if (replicaSet == null) {
            continue;
          }
          registerOrIgnore(replicaSet, true);
        }
      }
    }
  }

  private ReplicaSet getReplicaSetFromUid(String uid) {
    return client
        .apps()
        .replicaSets()
        .inAnyNamespace()
        .withLabel(LabelKeys.SERVER_DISCOVERY.getKey(), "true")
        .withLabel(LabelKeys.SERVER_NAME.getKey())
        .list()
        .getItems()
        .stream()
        .filter(replicaSet -> replicaSet.getMetadata().getUid().equals(uid))
        .findAny()
        .orElse(null);
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
