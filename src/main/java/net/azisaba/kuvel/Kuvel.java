package net.azisaba.kuvel;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import lombok.Getter;
import net.azisaba.kuvel.config.KuvelConfig;
import net.azisaba.kuvel.discovery.impl.RedisLoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.impl.RedisServerDiscovery;
import net.azisaba.kuvel.discovery.impl.SimpleLoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.impl.SimpleServerDiscovery;
import net.azisaba.kuvel.listener.LoadBalancerListener;
import net.azisaba.kuvel.redis.ProxyIdProvider;
import net.azisaba.kuvel.redis.RedisConnectionLeader;
import net.azisaba.kuvel.redis.RedisSubscriber;

@Plugin(
    id = "kuvel",
    name = "Kuvel",
    version = "1.0.0",
    url = "https://github.com/AzisabaNetwork/Kuvel",
    description =
        "Server-discovery Velocity plugin for Minecraft servers running in a Kubernetes cluster.",
    authors = {"Azisaba Network"})
@Getter
public class Kuvel {

  private final ProxyServer proxy;
  private final Logger logger;

  private final KubernetesClient client = new DefaultKubernetesClient();
  private KuvelServiceHandler kuvelServiceHandler;
  private RedisConnectionLeader redisConnectionLeader;
  private ProxyIdProvider proxyIdProvider;
  private RedisSubscriber redisSubscriber;

  private KuvelConfig kuvelConfig;

  @Inject
  public Kuvel(ProxyServer server, Logger logger) {
    this.proxy = server;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    kuvelConfig = new KuvelConfig(this);
    try {
      kuvelConfig.load();
    } catch (Exception e) {
      logger.severe("Failed to load config file. Plugin feature will be disabled.");
      e.printStackTrace();
      return;
    }

    kuvelServiceHandler = new KuvelServiceHandler(this, client);
    if (kuvelConfig.isRedisEnabled()) {
      Objects.requireNonNull(kuvelConfig.getRedisConnectionData());
      Objects.requireNonNull(kuvelConfig.getProxyGroupName());

      proxyIdProvider =
          new ProxyIdProvider(
              kuvelConfig.getRedisConnectionData().createJedisPool(),
              kuvelConfig.getProxyGroupName());
      proxyIdProvider.runTask(proxy, this);

      redisConnectionLeader =
          new RedisConnectionLeader(
              kuvelConfig.getRedisConnectionData().createJedisPool(),
              kuvelConfig.getProxyGroupName(),
              proxyIdProvider.getId());

      redisConnectionLeader.trySwitch();
      if (redisConnectionLeader.isLeader()) {
        logger.info("This proxy is selected as leader.");
      }
      kuvelServiceHandler.setAndRunLoadBalancerDiscovery(
          new RedisLoadBalancerDiscovery(
              client,
              this,
              kuvelConfig.getRedisConnectionData().createJedisPool(),
              kuvelConfig.getProxyGroupName(),
              redisConnectionLeader,
              kuvelServiceHandler));

      kuvelServiceHandler.setAndRunServerDiscovery(
          new RedisServerDiscovery(
              client,
              this,
              kuvelConfig.getRedisConnectionData().createJedisPool(),
              kuvelConfig.getProxyGroupName(),
              redisConnectionLeader,
              kuvelServiceHandler));

      proxy
          .getScheduler()
          .buildTask(
              this,
              () -> {
                if (redisConnectionLeader.isLeader()) {
                  redisConnectionLeader.extendLeader();
                } else {
                  redisConnectionLeader.trySwitch();
                }
              })
          .repeat(5, TimeUnit.MINUTES)
          .schedule();

      redisSubscriber =
          new RedisSubscriber(
              kuvelConfig.getRedisConnectionData().createJedisPool(),
              this,
              kuvelConfig.getProxyGroupName(),
              kuvelServiceHandler,
              redisConnectionLeader);
      redisSubscriber.subscribe();
    } else {
      kuvelServiceHandler.setAndRunLoadBalancerDiscovery(
          new SimpleLoadBalancerDiscovery(client, this, kuvelServiceHandler));
      kuvelServiceHandler.setAndRunServerDiscovery(
          new SimpleServerDiscovery(client, this, kuvelServiceHandler));
    }

    proxy.getEventManager().register(this, new LoadBalancerListener(this, kuvelServiceHandler));
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (kuvelServiceHandler != null) {
      kuvelServiceHandler.shutdown();
    }
    if (redisConnectionLeader != null) {
      redisConnectionLeader.leaveLeader();
    }
    if (proxyIdProvider != null) {
      proxyIdProvider.deleteProxyId();
    }
  }
}
