package net.azisaba.kuvel.redis;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

@RequiredArgsConstructor
public class ProxyIdProvider {

  private final JedisPool jedisPool;
  private final String groupName;

  private String id;

  public String getId() {
    if (id != null) {
      return id;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      String idTmp = null;
      while (idTmp == null) {
        idTmp = RandomStringUtils.randomAlphanumeric(8);
        String key = RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + idTmp;

        String result = jedis.set(key, "using", SetParams.setParams().nx().ex(300));

        if (result == null) {
          idTmp = null;
        }
      }

      id = idTmp;
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
                jedis.expire(RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + id, 300);
              }
            })
        .repeat(2, TimeUnit.MINUTES)
        .schedule();
  }

  public void deleteProxyId() {
    if (id == null) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(RedisKeys.PROXY_ID_PREFIX.getKey() + groupName + ":" + id);
    }
  }
}
