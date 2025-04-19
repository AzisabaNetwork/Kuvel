package net.azisaba.kuvel.redis;

import redis.clients.jedis.Jedis;

/**
 * Interface to abstract JedisPool and JedisSentinelPool operations.
 * This allows the code to work with both standalone Redis and Redis Sentinel.
 */
public interface JedisPoolWrapper {
    
    /**
     * Gets a Jedis resource from the pool.
     * 
     * @return Jedis resource
     */
    Jedis getResource();
    
    /**
     * Closes the pool.
     */
    void close();
}