package net.azisaba.kuvel.redis;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

@RequiredArgsConstructor
public class RedisSubscriberExecutor {

  private final JedisPool jedisPool;
  private final String groupName;

  @Getter @Setter private ExecutorService executorService = Executors.newFixedThreadPool(1);

  public void subscribe(
      Kuvel plugin,
      KuvelServiceHandler kuvelServiceHandler,
      RedisConnectionLeader redisConnectionLeader) {

    JedisPubSub subscriber =
        new RedisSubscriber(plugin, groupName, kuvelServiceHandler, redisConnectionLeader);

    Runnable task =
        () -> {
          try (Jedis jedis = jedisPool.getResource()) {
            jedis.psubscribe(
                subscriber, RedisKeys.NOTIFY_CHANNEL_PREFIX.getKey() + "*:" + groupName);
          }
        };

    executorService.submit(getDelayRunnable(executorService, task));
  }

  private Runnable getDelayRunnable(ExecutorService executor, Runnable runnable) {
    return () -> {
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      try {
        runnable.run();
      } catch (Exception ex) {
        ex.printStackTrace();
      } finally {
        executor.submit(getDelayRunnable(executor, runnable));
      }
    };
  }
}
