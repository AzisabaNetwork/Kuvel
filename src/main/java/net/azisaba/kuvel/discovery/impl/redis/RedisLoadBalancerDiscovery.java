package net.azisaba.kuvel.discovery.impl.redis;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.LoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.diffchecker.ReplicaSetDiffChecker;
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

  private final AtomicReference<ScheduledTask> taskReference = new AtomicReference<>();
  private final ReplicaSetDiffChecker replicaSetDiffChecker = new ReplicaSetDiffChecker().init();
  private final ReentrantLock lock = new ReentrantLock();

  private final HashMap<String, ArrayDeque<String>> loadBalancerDeleteWaitQueues = new HashMap<>();

  @Override
  public void start() {
    if (!redisConnectionLeader.isLeader()) {
      return;
    }

    Runnable runnable =
        () -> {
          FilterWatchListDeletable<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> request = client.apps()
              .replicaSets().inAnyNamespace();

          for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
            request = request.withLabel(e.getKey(), e.getValue());
          }

          List<ReplicaSet> replicaSetList = request.list().getItems();

          for (ReplicaSet replicaSet : replicaSetList) {
            if (replicaSetDiffChecker.diff(replicaSet)) {
              processUpdatedReplicaSet(replicaSet);
            }
          }

          List<String> deletedReplicaSetUid =
              replicaSetDiffChecker.getDeletedReplicaSetUidList(client);

          for (String uid : deletedReplicaSetUid) {
            unregisterOrIgnore(uid);
          }
        };

    taskReference.getAndUpdate(
        task -> {
          if (task != null) {
            task.cancel();
          }

          return plugin
              .getProxy()
              .getScheduler()
              .buildTask(plugin, runnable)
              .repeat(5, TimeUnit.SECONDS)
              .schedule();
        });
  }

  private void processUpdatedReplicaSet(ReplicaSet replicaSet) {
    lock.lock();
    try {
      if (replicaSet.getStatus().getReplicas() <= 0) {
        unregisterOrIgnore(replicaSet.getMetadata().getUid());
      } else {
        registerOrIgnore(replicaSet);
      }
    } finally {
      lock.unlock();
    }
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
        replicaSet
            .getMetadata()
            .getLabels()
            .getOrDefault(LabelKeys.PREFERRED_SERVER_NAME.getKey(), null);
    boolean initialServer =
        replicaSet
            .getMetadata()
            .getLabels()
            .getOrDefault(LabelKeys.INITIAL_SERVER.getKey(), "false")
            .equalsIgnoreCase("true");

    if (serverName == null) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      if (!isFetchedFromRedis) {
        Collection<String> loadBalancerNames =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName).values();

        if (loadBalancerNames.contains(serverName)
            || plugin.getProxy().getServer(serverName).isPresent()) {

          if (loadBalancerDeleteWaitQueues.containsKey(serverName)) {
            ArrayDeque<String> queue = loadBalancerDeleteWaitQueues.get(serverName);
            if (!queue.contains(uid)) {
              queue.add(uid);
            }
          } else {
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(uid);
            loadBalancerDeleteWaitQueues.put(serverName, queue);
          }
          return;
        }
      }

      kuvelServiceHandler.getReplicaSetUidAndServerNameMap().register(uid, serverName);
      jedis.hset(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName, uid, serverName);

      redisConnectionLeader.publishNewLoadBalancer(uid, serverName, initialServer);

      RegisteredServer server =
          plugin
              .getProxy()
              .registerServer(new ServerInfo(serverName, new InetSocketAddress("0.0.0.0", 0)));

      kuvelServiceHandler.registerLoadBalancer(
          new LoadBalancer(
              plugin.getProxy(),
              server,
              new RoundRobinLoadBalancingStrategy(),
              uid,
              initialServer));
    }
  }

  private void unregisterOrIgnore(String uid) {
    String serverName =
        kuvelServiceHandler.getReplicaSetUidAndServerNameMap().getServerNameFromUid(uid);

    if (serverName == null) {
      return;
    }

    kuvelServiceHandler.unregisterLoadBalancer(uid);
    redisConnectionLeader.publishDeletedLoadBalancer(uid);

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.hdel(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName, uid);
    }

    ArrayDeque<String> nextUidQueue = loadBalancerDeleteWaitQueues.get(serverName);
    if (nextUidQueue == null) {
      return;
    }

    ReplicaSet nextReplicaSet = null;

    while (!nextUidQueue.isEmpty() && nextReplicaSet == null) {
      String nextUid = nextUidQueue.poll();
      nextReplicaSet = getReplicaSetFromUid(nextUid);
    }

    if (nextReplicaSet == null) {
      return;
    }

    final ReplicaSet finalNextReplicaSet = nextReplicaSet;

    plugin
        .getProxy()
        .getScheduler()
        .buildTask(plugin, () -> registerOrIgnore(finalNextReplicaSet))
        .delay(0, TimeUnit.SECONDS)
        .schedule();
  }

  @Override
  public void shutdown() {
    taskReference.getAndUpdate(
        task -> {
          if (task != null) {
            task.cancel();
          }
          return null;
        });
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

        FilterWatchListDeletable<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> request = client.apps()
            .replicaSets().inAnyNamespace();

        for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
          request = request.withLabel(e.getKey(), e.getValue());
        }

        request.list()
            .getItems()
            .stream()
            .filter(replicaSet -> replicaSet.getStatus().getReplicas() > 0)
            .filter(replicaSet ->
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
    FilterWatchListDeletable<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> request = client.apps()
        .replicaSets().inAnyNamespace();

    for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
      request = request.withLabel(e.getKey(), e.getValue());
    }

    return request
        .list()
        .getItems()
        .stream()
        .filter(replicaSet -> replicaSet.getMetadata().getUid().equals(uid))
        .findAny()
        .orElse(null);
  }
}
