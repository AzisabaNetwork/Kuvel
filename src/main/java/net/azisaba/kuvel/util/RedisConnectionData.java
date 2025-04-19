package net.azisaba.kuvel.util;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.Data;
import net.azisaba.kuvel.redis.JedisPoolWrapper;
import net.azisaba.kuvel.redis.JedisPoolWrapperImpl;
import net.azisaba.kuvel.redis.JedisSentinelPoolWrapperImpl;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Protocol;

@Data
public class RedisConnectionData {

  // Standalone mode fields
  private final String hostname;
  private final int port;

  // Common fields
  private final String username;
  private final String password;

  // Sentinel mode fields
  private final String masterName;
  private final Set<String> sentinels;
  private final boolean sentinelMode;

  // Constructor for standalone mode
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
    this.masterName = null;
    this.sentinels = null;
    this.sentinelMode = false;
  }

  // Constructor for sentinel mode
  public RedisConnectionData(String masterName, Set<String> sentinels, String username, String password) {
    if (Objects.equals(username, "")) {
      username = null;
    }
    if (Objects.equals(password, "")) {
      password = null;
    }

    this.hostname = null;
    this.port = -1;
    this.username = username;
    this.password = password;
    this.masterName = masterName;
    this.sentinels = new HashSet<>(sentinels);
    this.sentinelMode = true;
  }

  public RedisConnectionData(String hostname, int port) {
    this(hostname, port, null, null);
  }

  public RedisConnectionData(String hostname, int port, String password) {
    this(hostname, port, null, password);
  }

  /**
   * Creates a JedisPool for standalone Redis mode.
   * @deprecated Use {@link #createPoolWrapper()} instead to support both standalone and sentinel modes.
   * @return JedisPool instance
   * @throws IllegalStateException if called in sentinel mode
   */
  @Deprecated
  public JedisPool createJedisPool() {
    if (sentinelMode) {
      throw new IllegalStateException("Cannot create JedisPool in sentinel mode. Use createPoolWrapper() instead.");
    }

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

  /**
   * Creates a JedisPoolWrapper that works with either standalone Redis or Redis Sentinel.
   * 
   * @return JedisPoolWrapper instance
   */
  public JedisPoolWrapper createPoolWrapper() {
    if (sentinelMode) {
      JedisPoolConfig poolConfig = new JedisPoolConfig();
      JedisSentinelPool sentinelPool;

      if (username != null && password != null) {
        sentinelPool = new JedisSentinelPool(masterName, sentinels, poolConfig, 
            Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT, username, password, Protocol.DEFAULT_DATABASE);
      } else if (password != null) {
        sentinelPool = new JedisSentinelPool(masterName, sentinels, poolConfig, 
            Protocol.DEFAULT_TIMEOUT, password, Protocol.DEFAULT_DATABASE);
      } else {
        sentinelPool = new JedisSentinelPool(masterName, sentinels);
      }

      return new JedisSentinelPoolWrapperImpl(sentinelPool);
    } else {
      return new JedisPoolWrapperImpl(createJedisPool());
    }
  }
}
