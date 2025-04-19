package net.azisaba.kuvel.redis;

import lombok.RequiredArgsConstructor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisSentinelPool;

/**
 * Implementation of JedisPoolWrapper for JedisSentinelPool (Redis Sentinel).
 */
@RequiredArgsConstructor
public class JedisSentinelPoolWrapperImpl implements JedisPoolWrapper {
    
    private final JedisSentinelPool sentinelPool;
    
    @Override
    public Jedis getResource() {
        return sentinelPool.getResource();
    }
    
    @Override
    public void close() {
        sentinelPool.close();
    }
}