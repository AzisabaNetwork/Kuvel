package net.azisaba.kuvel.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum LabelKeys {
  PREFERRED_SERVER_NAME("preferred-server-name"),
  INITIAL_SERVER("initial-server"),
  DISABLE_NAME_SUFFIX("disable-name-suffix");

  private final String key;

  public String getKey(String prefix) {
    return prefix + "/" + key;
  }

  @Override
  public String toString() {
    return key;
  }
}
