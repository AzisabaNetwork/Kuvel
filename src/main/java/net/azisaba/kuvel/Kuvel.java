package net.azisaba.kuvel;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.Getter;
import net.azisaba.kuvel.config.KuvelConfig;
import net.azisaba.kuvel.discovery.impl.redis.RedisLoadBalancerDiscovery;
import net.azisaba.kuvel.discovery.impl.redis.RedisServerDiscovery;
import net.azisaba.kuvel.listener.ChooseInitialServerListener;
import net.azisaba.kuvel.listener.LoadBalancerListener;
import net.azisaba.kuvel.redis.ProxyIdProvider;
import net.azisaba.kuvel.redis.RedisConnectionLeader;
import net.azisaba.kuvel.redis.RedisSubscriberExecutor;
import org.slf4j.Logger;

@Plugin(
    id = "kuvel",
    name = "Kuvel",
    version = "3.0.0-rc3",
    url = "https://github.com/AzisabaNetwork/Kuvel",
    description =
        "Server-discovery Velocity plugin for Minecraft servers running in a Kubernetes cluster.",
    authors = {"Azisaba Network"})
@Getter
public class Kuvel {

  private final ProxyServer proxy;
  private final Logger logger;
  private final File dataDirectory;

  private KubernetesClient client;
  private KuvelServiceHandler kuvelServiceHandler;
  private RedisConnectionLeader redisConnectionLeader;
  private ProxyIdProvider proxyIdProvider;
  private RedisSubscriberExecutor redisSubscriberExecutor;

  private KuvelConfig kuvelConfig;

  @Inject
  public Kuvel(ProxyServer server, org.slf4j.Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory.toFile();
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      client = new KubernetesClientBuilder().build();
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }

    kuvelConfig = new KuvelConfig(this);
    try {
      kuvelConfig.load();
    } catch (Exception e) {
      logger.error("Failed to load config file. Plugin feature will be disabled.", e);
      return;
    }

    if (kuvelConfig.getLabelSelectors().isEmpty()) {
      logger.error("No label selectors are specified. Plugin feature will be disabled.");
      return;
    }

    getLogger().info("Loaded " + kuvelConfig.getLabelSelectors().size() + " selectors:");
    for (Map.Entry<String, String> entry : kuvelConfig.getLabelSelectors().entrySet()) {
      getLogger().info(" - " + entry.getKey() + ": " + entry.getValue());
    }

    kuvelServiceHandler = new KuvelServiceHandler(this, client, kuvelConfig.getNamespace());

    Objects.requireNonNull(kuvelConfig.getRedisConnectionData());
    Objects.requireNonNull(kuvelConfig.getProxyGroupName());

    proxyIdProvider =
        new ProxyIdProvider(
            kuvelConfig.getRedisConnectionData().createJedisPool(),
            kuvelConfig.getProxyGroupName());
    proxyIdProvider.runTask(proxy, this);

    logger.info("This proxy's id is: " + proxyIdProvider.getId());

    redisConnectionLeader =
        new RedisConnectionLeader(
            this,
            kuvelConfig.getRedisConnectionData().createJedisPool(),
            kuvelConfig.getProxyGroupName(),
            proxyIdProvider.getId());

    redisConnectionLeader.trySwitch();

    kuvelServiceHandler.setAndRunLoadBalancerDiscovery(
        new RedisLoadBalancerDiscovery(
            client,
            this,
            kuvelConfig.getNamespace(),
            kuvelConfig.getRedisConnectionData().createJedisPool(),
            kuvelConfig.getProxyGroupName(),
            redisConnectionLeader,
            kuvelServiceHandler));

    kuvelServiceHandler.setAndRunServerDiscovery(
        new RedisServerDiscovery(
            client,
            this,
            kuvelConfig.getNamespace(),
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
                redisConnectionLeader.extendLeaderExpire();
              } else {
                redisConnectionLeader.trySwitch();
              }
            })
        .repeat(5, TimeUnit.SECONDS)
        .schedule();

    redisSubscriberExecutor =
        new RedisSubscriberExecutor(
            kuvelConfig.getRedisConnectionData().createJedisPool(),
            kuvelConfig.getProxyGroupName());
    redisSubscriberExecutor.subscribe(this, kuvelServiceHandler, redisConnectionLeader);

    proxy.getEventManager().register(this, new LoadBalancerListener(kuvelServiceHandler));
    proxy
        .getEventManager()
        .register(this, new ChooseInitialServerListener(proxy, kuvelServiceHandler));
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
