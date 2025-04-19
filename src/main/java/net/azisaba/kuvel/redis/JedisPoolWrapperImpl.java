package net.azisaba.kuvel.redis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Implementation of JedisPoolWrapper for JedisPool (standalone Redis).
 */
@RequiredArgsConstructor
public class JedisPoolWrapperImpl implements JedisPoolWrapper {

    @Getter
    private final JedisPool jedisPool;

    @Override
    public Jedis getResource() {
        return jedisPool.getResource();
    }

    @Override
    public void close() {
        jedisPool.close();
    }
}
