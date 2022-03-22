package net.azisaba.kuvel.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class VelocityConfigLoader {

  private final File file;
  private final HashMap<String, Object> dataMap = new HashMap<>();

  public static VelocityConfigLoader load(File file) {
    return new VelocityConfigLoader(file).load();
  }

  public String getString(@Nonnull String key, String defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    if (dataMap.containsKey(lowerKey)) {
      return dataMap.get(lowerKey).toString();
    } else {
      return defaultValue;
    }
  }

  public int getInt(@Nonnull String key, int defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    if (dataMap.containsKey(lowerKey)) {
      return Integer.parseInt(dataMap.get(lowerKey).toString());
    } else {
      return defaultValue;
    }
  }

  public long getLong(@Nonnull String key, long defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    if (dataMap.containsKey(lowerKey)) {
      return Long.parseLong(dataMap.get(lowerKey).toString());
    } else {
      return defaultValue;
    }
  }

  public boolean getBoolean(@Nonnull String key, boolean defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    if (dataMap.containsKey(lowerKey)) {
      return Boolean.parseBoolean(dataMap.get(lowerKey).toString());
    } else {
      return defaultValue;
    }
  }

  public double getDouble(@Nonnull String key, double defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    if (dataMap.containsKey(lowerKey)) {
      return Double.parseDouble(dataMap.get(lowerKey).toString());
    } else {
      return defaultValue;
    }
  }

  public @Nullable Object get(@Nonnull String key, Object defaultValue) {
    String lowerKey = key.toLowerCase(Locale.ROOT);
    return dataMap.getOrDefault(lowerKey, defaultValue);
  }

  public String getString(@Nonnull String key) {
    return getString(key, null);
  }

  public int getInt(@Nonnull String key) {
    return getInt(key, 0);
  }

  public long getLong(@Nonnull String key) {
    return getLong(key, 0);
  }

  public boolean getBoolean(@Nonnull String key) {
    return getBoolean(key, false);
  }

  public double getDouble(@Nonnull String key) {
    return getDouble(key, 0);
  }

  public @Nullable Object get(@Nonnull String key) {
    return get(key, null);
  }

  private VelocityConfigLoader load() {
    Objects.requireNonNull(file);
    String fileName = file.getName().toLowerCase(Locale.ROOT);

    if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml")) {
      throw new IllegalArgumentException("File must be a YAML file.");
    }

    Yaml yaml = new Yaml();
    Map<String, Object> data;
    try {
      if (file.exists()) {
        data = yaml.load(new FileReader(file));
      } else {
        data = new HashMap<>();
      }
    } catch (FileNotFoundException e) {
      throw new IllegalArgumentException("File not found.", e);
    }

    storeDataFor(null, data);
    return this;
  }

  public void saveDefaultConfig() throws IOException {
    if (file.exists()) {
      return;
    }

    InputStream is = getClass().getClassLoader().getResourceAsStream(file.getName());
    if (is == null) {
      throw new IllegalStateException("Failed to load config.yml from resource.");
    }
    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    Files.createDirectories(file.getParentFile().toPath());

    byte[] byteStr =
        reader
            .lines()
            .collect(Collectors.joining(System.lineSeparator()))
            .getBytes(StandardCharsets.UTF_8);

    Files.write(file.toPath(), byteStr);

    load();
  }

  private void storeDataFor(@Nullable String parentKey, Map<String, Object> map) {
    for (String key : map.keySet()) {
      String newKey = parentKey == null ? key : parentKey + "." + key;
      newKey = newKey.toLowerCase(Locale.ROOT);
      if (canDig(map, key)) {
        storeDataFor(newKey, dig(map, key));
      } else {
        dataMap.put(newKey, map.get(key));
      }
    }
  }

  private boolean canDig(Map<String, Object> map, String key) {
    return map.get(key) instanceof Map;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> dig(Map<String, Object> data, String key) {
    Object o = data.get(key);
    if (!(o instanceof Map)) {
      throw new IllegalArgumentException("Cannot dig.");
    }

    return (Map<String, Object>) o;
  }
}
