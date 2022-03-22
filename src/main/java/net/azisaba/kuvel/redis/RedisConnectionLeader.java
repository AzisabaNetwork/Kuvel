package net.azisaba.kuvel.redis;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@RequiredArgsConstructor
public class RedisConnectionLeader {

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
      long result = jedis.setnx(key, proxyId);

      if (result == 1) {
        jedis.expire(key, 600);
        leader = true;
        leaderExpireAt = System.currentTimeMillis() + (600 * 1000);
        return true;
      } else {
        String currentLeader = jedis.get(RedisKeys.LEADER_PREFIX.getKey() + groupName);
        if (Objects.equals(proxyId, currentLeader)) {
          leader = true;
          return true;
        }
        return false;
      }
    }
  }

  public void extendLeader() {
    if (!trySwitch()) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.expire(RedisKeys.LEADER_PREFIX.getKey() + groupName, 600);
      leaderExpireAt = System.currentTimeMillis() + (600 * 1000);
    }
  }

  public void leaveLeader() {
    try (Jedis jedis = jedisPool.getResource()) {
      String currentLeader = jedis.get(RedisKeys.LEADER_PREFIX.getKey() + groupName);
      if (!Objects.equals(proxyId, currentLeader)) {
        return;
      }

      jedis.del(RedisKeys.LEADER_PREFIX.getKey() + groupName);
    }
  }

  public void publishNewLoadBalancer(String replicaSetUid, String serverName) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.publish(
          RedisKeys.LOAD_BALANCER_ADDED_NOTIFY_PREFIX.getKey() + groupName,
          replicaSetUid + ":" + serverName);
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
}
