package net.azisaba.kuvel.loadbalancer;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class LoadBalancer {

  private final ProxyServer proxy;
  private final RegisteredServer server;
  private final LoadBalancingStrategy strategy;
  private final String replicaSetUid;

  private final List<String> endpointServers = new ArrayList<>();

  public void addEndpoint(String serverName) {
    if (!endpointServers.contains(serverName)) {
      endpointServers.add(serverName);
    }
  }

  public void removeEndpoint(String serverName) {
    endpointServers.remove(serverName);
  }

  public void setEndpoints(List<String> endpoints) {
    endpointServers.clear();
    endpointServers.addAll(endpoints);
  }

  public RegisteredServer getTarget() {
    List<RegisteredServer> servers =
        endpointServers.stream()
            .map(name -> proxy.getServer(name).orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return strategy.choose(servers);
  }

  //  public List<RegisteredServer> getTargets(int count) {
  //    List<RegisteredServer> servers =
  //        endpointServers.stream()
  //            .map(name -> proxy.getServer(name).orElse(null))
  //            .filter(Objects::nonNull)
  //            .collect(Collectors.toList());
  //
  //    return strategy.choose(servers, count);
  //  }
}
