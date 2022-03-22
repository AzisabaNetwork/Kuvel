package net.azisaba.kuvel.redis;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@RequiredArgsConstructor
public class ProxyIdProvider {

  private final JedisPool jedisPool;
  private final String groupName;

  private String id;

  public String getId() {
    if (id == null) {
      String idTmp = RandomStringUtils.randomAlphanumeric(8);

      try (Jedis jedis = jedisPool.getResource()) {
        while (true) {
          String key = RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + idTmp;
          long result = jedis.setnx(key, "true");

          if (result == 1) {
            jedis.expire(key, 600);
            id = idTmp;
            break;
          }

          idTmp = RandomStringUtils.randomAlphanumeric(8);
        }
      }
    }
    return id;
  }

  public void runTask(ProxyServer proxy, Object plugin) {
    proxy
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              try (Jedis jedis = jedisPool.getResource()) {
                jedis.expire(RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + id, 600);
              }
            })
        .repeat(5, TimeUnit.MINUTES)
        .schedule();
  }

  public void deleteProxyId() {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + id);
    }
  }
}
