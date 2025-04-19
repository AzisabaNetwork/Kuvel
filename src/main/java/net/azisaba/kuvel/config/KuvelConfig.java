package net.azisaba.kuvel.config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.util.RedisConnectionData;

@Getter
@RequiredArgsConstructor
public class KuvelConfig {

  private final Kuvel plugin;

  private static final String CONFIG_FILE_NAME = "config.yml";

  @Nullable private String namespace;
  private boolean redisEnabled;
  @Nullable private RedisConnectionData redisConnectionData;
  @Nullable private String proxyGroupName;
  private String labelKeyPrefix;

  public void load() throws IOException {
    File uppercaseDataFolder = new File(plugin.getDataDirectory().getParentFile(), "Kuvel");
    if (uppercaseDataFolder.exists() && !plugin.getDataDirectory().exists()) {
      if (uppercaseDataFolder.renameTo(plugin.getDataDirectory())) {
        plugin
           .getLogger()
           .info(
               "Successfully renamed the data folder to use a lowercase name.");
      } else {
        plugin
            .getLogger()
            .warn(
                "Failed to rename the data folder to be lowercase. Please manually rename the data folder to 'kuvel'.");
      }
    }

    VelocityConfigLoader conf = VelocityConfigLoader.load(new File(plugin.getDataDirectory(), CONFIG_FILE_NAME));
    conf.saveDefaultConfig();

    Map<String, String> env = System.getenv();

    namespace = env.getOrDefault("KUVEL_NAMESPACE", conf.getString("namespace", null));

    // Check if Redis Sentinel mode is enabled
    boolean sentinelMode = env.containsKey("KUVEL_REDIS_SENTINEL_ENABLED") 
        ? Boolean.parseBoolean(env.get("KUVEL_REDIS_SENTINEL_ENABLED"))
        : conf.getBoolean("redis.sentinel.enabled", false);

    String username = env.getOrDefault("KUVEL_REDIS_CONNECTION_USERNAME", conf.getString("redis.connection.username"));
    String password = env.getOrDefault("KUVEL_REDIS_CONNECTION_PASSWORD", conf.getString("redis.connection.password"));

    if (sentinelMode) {
      // Load Sentinel configuration
      String masterName = env.getOrDefault("KUVEL_REDIS_SENTINEL_MASTER", conf.getString("redis.sentinel.master"));
      String sentinelsStr = env.getOrDefault("KUVEL_REDIS_SENTINEL_NODES", conf.getString("redis.sentinel.nodes"));

      if (masterName == null || sentinelsStr == null) {
        redisEnabled = false;
        plugin
            .getLogger()
            .warn(
                "Redis Sentinel is enabled, but master name or sentinel nodes are missing. Redis sync will be disabled.");
      } else {
        // Parse sentinel nodes (comma-separated list of host:port)
        Set<String> sentinels = new HashSet<>(Arrays.asList(sentinelsStr.split(",")));
        redisConnectionData = new RedisConnectionData(masterName, sentinels, username, password);
        redisEnabled = true;
        plugin.getLogger().info("Redis Sentinel mode enabled with master: " + masterName);
      }
    } else {
      // Load standalone configuration
      String hostname = env.getOrDefault("KUVEL_REDIS_CONNECTION_HOSTNAME", conf.getString("redis.connection.hostname"));
      int port = conf.getInt("redis.connection.port", -1);
      if (env.containsKey("KUVEL_REDIS_CONNECTION_PORT")) {
        try {
          port = Integer.parseInt(env.get("KUVEL_REDIS_CONNECTION_PORT"));
        } catch (NumberFormatException e) {
          plugin
              .getLogger()
              .warn(
                  "Invalid port number for Redis connection specified in KUVEL_REDIS_CONNECTION_PORT environment variable. Using port " + port + " from config.yml.");
        }
      }

      if (hostname == null || port <= 0) {
        redisEnabled = false;
        plugin
            .getLogger()
            .warn(
                "Redis is enabled, but hostname or port is invalid. Redis sync will be disabled.");
      } else {
        redisConnectionData = new RedisConnectionData(hostname, port, username, password);
        redisEnabled = true;
        plugin.getLogger().info("Redis standalone mode enabled with host: " + hostname + ":" + port);
      }
    }

    proxyGroupName = env.getOrDefault("KUVEL_REDIS_GROUPNAME", conf.getString("redis.group-name", null));
    labelKeyPrefix = env.getOrDefault("KUVEL_LABEL_KEY_PREFIX", conf.getString("label-key-prefix", "kuvel.azisaba.net"));
  }
}
