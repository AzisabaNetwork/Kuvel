package net.azisaba.kuvel.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum LabelKeys {
  ENABLE_SERVER_DISCOVERY("enable-server-discovery"),
  PREFERRED_SERVER_NAME("preferred-server-name"),
  INITIAL_SERVER("initial-server");

  private final String key;

  public String getKey(String prefix) {
    return prefix + "/" + key;
  }

  @Override
  public String toString() {
    return key;
  }
}
