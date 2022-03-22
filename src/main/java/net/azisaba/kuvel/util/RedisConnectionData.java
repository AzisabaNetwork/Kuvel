package net.azisaba.kuvel.util;

import java.util.Objects;
import lombok.Data;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Data
public class RedisConnectionData {

  private final String hostname;
  private final int port;

  private final String username;
  private final String password;

  public RedisConnectionData(String hostname, int port, String username, String password) {
    if (Objects.equals(username, "")) {
      username = null;
    }
    if (Objects.equals(password, "")) {
      password = null;
    }

    this.hostname = hostname;
    this.port = port;
    this.username = username;
    this.password = password;
  }

  public RedisConnectionData(String hostname, int port) {
    this(hostname, port, null, null);
  }

  public RedisConnectionData(String hostname, int port, String password) {
    this(hostname, port, null, password);
  }

  public JedisPool createJedisPool() {
    if (username != null && password != null) {
      return new JedisPool(hostname, port, username, password);
    } else if (password != null) {
      return new JedisPool(new JedisPoolConfig(), hostname, port, 3000, password);
    } else if (username != null) {
      throw new IllegalArgumentException(
          "Redis password cannot be null if redis username is not null");
    } else {
      return new JedisPool(new JedisPoolConfig(), hostname, port);
    }
  }
}
