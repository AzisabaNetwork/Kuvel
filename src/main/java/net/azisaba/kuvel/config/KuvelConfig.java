package net.azisaba.kuvel.config;

import java.io.File;
import java.io.IOException;
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

  public void load() throws IOException {
    VelocityConfigLoader conf = VelocityConfigLoader.load(new File(plugin.getDataDirectory(), CONFIG_FILE_NAME));
    conf.saveDefaultConfig();

    namespace = conf.getString("namespace", null);

    String hostname = conf.getString("redis.connection.hostname");
    int port = conf.getInt("redis.connection.port", -1);
    String username = conf.getString("redis.connection.username");
    String password = conf.getString("redis.connection.password");

    if (hostname == null || port <= 0) {
      redisEnabled = false;
      plugin
          .getLogger()
          .warning(
              "Redis is enabled, but hostname or port is invalid. Redis sync will be disabled.");
    } else {
      redisConnectionData = new RedisConnectionData(hostname, port, username, password);
    }

    proxyGroupName = conf.getString("redis.group-name", null);
  }
}
