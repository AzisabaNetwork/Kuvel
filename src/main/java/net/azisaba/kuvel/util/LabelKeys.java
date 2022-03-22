package net.azisaba.kuvel.util;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum LabelKeys {
  SERVER_DISCOVERY("minecraftServiceDiscovery"),
  SERVER_NAME("minecraftServerName");

  @Getter private final String key;

  @Override
  public String toString() {
    return getKey();
  }
}
