package net.azisaba.kuvel.redis;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum RedisKeys {
  LEADER_PREFIX("kuvel:leader:"),
  PROXY_ID_PREFIX("kuvel:proxy-id:"),
  SERVERS_PREFIX("kuvel:servers:"),
  LOAD_BALANCERS_PREFIX("kuvel:load-balancers:"),

  NOTIFY_CHANNEL_PREFIX("kuvel:notify:"),
  POD_ADDED_NOTIFY_PREFIX("kuvel:notify:add:pod:"),
  LOAD_BALANCER_ADDED_NOTIFY_PREFIX("kuvel:notify:add:lb:"),
  POD_DELETED_NOTIFY_PREFIX("kuvel:notify:del:pod:"),
  LOAD_BALANCER_DELETED_NOTIFY_PREFIX("kuvel:notify:del:lb:");

  private final String key;

  public String getKey() {
    return key;
  }

  @Override
  public String toString() {
    return getKey();
  }
}
