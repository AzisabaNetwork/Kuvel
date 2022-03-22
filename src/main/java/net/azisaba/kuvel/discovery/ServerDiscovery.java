package net.azisaba.kuvel.discovery;

import io.fabric8.kubernetes.api.model.Pod;
import java.net.InetSocketAddress;
import java.util.HashMap;

public interface ServerDiscovery {

  void start();

  void shutdown();

  HashMap<String, Pod> getServersForStartup();
}
