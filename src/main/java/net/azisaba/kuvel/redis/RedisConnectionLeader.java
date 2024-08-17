package net.azisaba.kuvel.redis;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.discovery.impl.redis.RedisLoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.impl.redis.RedisServerDiscovery;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

@RequiredArgsConstructor
public class RedisConnectionLeader {

  private final Kuvel plugin;
  private final JedisPool jedisPool;
  private final String groupName;
  private final String proxyId;

  private boolean leader = false;
  private long leaderExpireAt = 0;

  public boolean isLeader() {
    if (leader && leaderExpireAt < System.currentTimeMillis()) {
      leader = false;
    }
    return leader;
  }

  public boolean trySwitch() {
    try (Jedis jedis = jedisPool.getResource()) {
      String key = RedisKeys.LEADER_PREFIX.getKey() + groupName;
      String result = jedis.set(key, proxyId, SetParams.setParams().nx().ex(10));

      if (result != null) {
        leader = true;
        leaderExpireAt = System.currentTimeMillis() + (10 * 1000);

        plugin.getLogger().info("This proxy was selected as a new leader.");
        jedis.publish(RedisKeys.LEADER_CHANGED_NOTIFY_PREFIX.getKey() + groupName, proxyId);
        runDiscoveryTask();
        return true;
      } else {
        String currentLeader = jedis.get(RedisKeys.LEADER_PREFIX.getKey() + groupName);
        if (Objects.equals(proxyId, currentLeader)) {
          leader = true;
          return true;
        }

        if (leader) {
          stopDiscoveryTask();
        }
        leader = false;
        return false;
      }
    }
  }

  public void extendLeaderExpire() {
    if (!trySwitch()) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.expire(RedisKeys.LEADER_PREFIX.getKey() + groupName, 10);
      leaderExpireAt = System.currentTimeMillis() + (10 * 1000);
    }
  }

  public void leaveLeader() {
    try (Jedis jedis = jedisPool.getResource()) {
      String currentLeader = jedis.get(RedisKeys.LEADER_PREFIX.getKey() + groupName);
      if (!Objects.equals(proxyId, currentLeader)) {
        return;
      }

      jedis.del(RedisKeys.LEADER_PREFIX.getKey() + groupName);
      jedis.publish(RedisKeys.LEADER_LEAVE_NOTIFY_PREFIX.getKey() + groupName, proxyId);
    }
  }

  public void publishNewLoadBalancer(
      String replicaSetUid, String serverName, boolean initialServer) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(
          RedisKeys.LOAD_BALANCER_ADDED_NOTIFY_PREFIX.getKey() + groupName,
          replicaSetUid + ":" + serverName + ":" + initialServer);
    }
  }

  public void publishNewServer(String podUid, String serverName) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(
          RedisKeys.POD_ADDED_NOTIFY_PREFIX.getKey() + groupName, podUid + ":" + serverName);
    }
  }

  public void publishDeletedLoadBalancer(String replicaSetUid) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(
          RedisKeys.LOAD_BALANCER_DELETED_NOTIFY_PREFIX.getKey() + groupName, replicaSetUid);
    }
  }

  public void publishDeletedServer(String podUid) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(RedisKeys.POD_DELETED_NOTIFY_PREFIX.getKey() + groupName, podUid);
    }
  }

  private void runDiscoveryTask() {
    if (plugin.getKuvelConfig().getRedisConnectionData() == null) {
      leaveLeader();
      return;
    }

    plugin
        .getKuvelServiceHandler()
        .setAndRunLoadBalancerDiscovery(
            new RedisLoadBalancerDiscovery(
                plugin.getClient(),
                plugin,
                plugin.getKuvelConfig().getNamespace(),
                plugin.getKuvelConfig().getRedisConnectionData().createJedisPool(),
                plugin.getKuvelConfig().getProxyGroupName(),
                this,
                plugin.getKuvelServiceHandler()));

    plugin
        .getKuvelServiceHandler()
        .setAndRunServerDiscovery(
            new RedisServerDiscovery(
                plugin.getClient(),
                plugin,
                plugin.getKuvelConfig().getNamespace(),
                plugin.getKuvelConfig().getRedisConnectionData().createJedisPool(),
                plugin.getKuvelConfig().getProxyGroupName(),
                this,
                plugin.getKuvelServiceHandler()));
  }

  private void stopDiscoveryTask() {
    plugin.getKuvelServiceHandler().setAndRunLoadBalancerDiscovery(null);
    plugin.getKuvelServiceHandler().setAndRunServerDiscovery(null);
  }
}
