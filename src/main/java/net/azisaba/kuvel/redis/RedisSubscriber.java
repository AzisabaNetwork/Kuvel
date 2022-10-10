package net.azisaba.kuvel.redis;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.loadbalancer.LoadBalancer;
import net.azisaba.kuvel.loadbalancer.strategy.impl.RoundRobinLoadBalancingStrategy;
import redis.clients.jedis.JedisPubSub;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class RedisSubscriber extends JedisPubSub {

  private final Kuvel plugin;
  private final String groupName;
  private final KuvelServiceHandler kuvelServiceHandler;
  private final RedisConnectionLeader redisConnectionLeader;

  @Override
  public void onPMessage(String pattern, String channel, String message) {
    String receivedGroupName = channel.split(":")[channel.split(":").length - 1];
    if (!receivedGroupName.equalsIgnoreCase(groupName)) {
      return;
    }

    if (channel.startsWith(RedisKeys.LEADER_CHANGED_NOTIFY_PREFIX.getKey())
        || channel.startsWith(RedisKeys.LEADER_LEAVE_NOTIFY_PREFIX.getKey())) {
      redisConnectionLeader.trySwitch();
      return;
    }

    if (redisConnectionLeader.isLeader()) {
      return;
    }

    if (channel.startsWith(RedisKeys.POD_ADDED_NOTIFY_PREFIX.getKey())) {
      String podUid = message.split(":")[0];
      String serverName = message.split(":")[1];

      kuvelServiceHandler.registerPod(podUid, serverName);
    } else if (channel.startsWith(RedisKeys.LOAD_BALANCER_ADDED_NOTIFY_PREFIX.getKey())) {
      String replicaSetUid = message.split(":")[0];
      String serverName = message.split(":")[1];
      boolean initialServer = Boolean.parseBoolean(message.split(":")[2]);

      RegisteredServer server =
          plugin
              .getProxy()
              .registerServer(new ServerInfo(serverName, new InetSocketAddress("0.0.0.0", 0)));
      LoadBalancer loadBalancer =
          new LoadBalancer(
              plugin.getProxy(),
              server,
              new RoundRobinLoadBalancingStrategy(),
              replicaSetUid,
              initialServer);
      kuvelServiceHandler.registerLoadBalancer(loadBalancer);
    } else if (channel.startsWith(RedisKeys.POD_DELETED_NOTIFY_PREFIX.getKey())) {
      kuvelServiceHandler.unregisterPod(message);
    } else if (channel.startsWith(RedisKeys.LOAD_BALANCER_DELETED_NOTIFY_PREFIX.getKey())) {
      kuvelServiceHandler.unregisterLoadBalancer(message);
    }
  }
}
