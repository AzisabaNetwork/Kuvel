package net.azisaba.kuvel.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum LabelKeys {
  PREFERRED_SERVER_NAME("preferred-server-name"),
  INITIAL_SERVER("initial-server");

  private final String key;

  public String getKey() {
    return "kuvel.azisaba.net/" + key;
  }

  @Override
  public String toString() {
    return getKey();
  }
}
