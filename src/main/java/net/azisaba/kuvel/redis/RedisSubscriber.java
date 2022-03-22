package net.azisaba.kuvel.redis;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.loadbalancer.LoadBalancer;
import net.azisaba.kuvel.loadbalancer.strategy.impl.RoundRobinLoadBalancingStrategy;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

@RequiredArgsConstructor
public class RedisSubscriber {

  private final JedisPool jedisPool;
  private final Kuvel plugin;
  private final String groupName;
  private final KuvelServiceHandler handler;
  private final RedisConnectionLeader redisConnectionLeader;

  @Getter @Setter private ExecutorService executorService = Executors.newFixedThreadPool(1);

  public void subscribe() {
    JedisPubSub subscriber =
        new JedisPubSub() {
          @Override
          public void onPMessage(String pattern, String channel, String message) {
            if (redisConnectionLeader.isLeader()) {
              return;
            }

            String receivedGroupName = channel.split(":")[channel.split(":").length - 1];
            if (!receivedGroupName.equalsIgnoreCase(groupName)) {
              return;
            }

            if (channel.startsWith(RedisKeys.POD_ADDED_NOTIFY_PREFIX.getKey())) {
              String podUid = message.split(":")[0];
              String serverName = message.split(":")[1];

              handler.registerPod(podUid, serverName);
            } else if (channel.startsWith(RedisKeys.LOAD_BALANCER_ADDED_NOTIFY_PREFIX.getKey())) {
              String replicaSetUid = message.split(":")[0];
              String serverName = message.split(":")[1];

              RegisteredServer server =
                  plugin
                      .getProxy()
                      .registerServer(
                          new ServerInfo(serverName, new InetSocketAddress("0.0.0.0", 0)));
              LoadBalancer loadBalancer =
                  new LoadBalancer(
                      plugin.getProxy(),
                      server,
                      new RoundRobinLoadBalancingStrategy(),
                      replicaSetUid);
              handler.registerLoadBalancer(loadBalancer);
            } else if (channel.startsWith(RedisKeys.POD_DELETED_NOTIFY_PREFIX.getKey())) {
              handler.unregisterPod(message);
            } else if (channel.startsWith(RedisKeys.LOAD_BALANCER_DELETED_NOTIFY_PREFIX.getKey())) {
              handler.unregisterLoadBalancer(message);
            }
          }
        };

    Runnable task =
        () -> {
          try (Jedis jedis = jedisPool.getResource()) {
            jedis.psubscribe(
                subscriber, RedisKeys.NOTIFY_CHANNEL_PREFIX.getKey() + "*:" + groupName);
          }
        };

    runsOnExecutor(executorService, task);
  }

  public void runsOnExecutor(ExecutorService executor, Runnable runnable) {
    executor.submit(runnable);

    for (int i = 0; i < 1000; i++) {
      executor.submit(
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
