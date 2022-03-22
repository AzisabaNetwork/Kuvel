package net.azisaba.kuvel.loadbalancer.strategy.impl;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import net.azisaba.kuvel.loadbalancer.LoadBalancingStrategy;

public class RoundRobinLoadBalancingStrategy implements LoadBalancingStrategy {

  private int lastIndex = 0;

  @Override
  public RegisteredServer choose(List<RegisteredServer> servers) {
    if (servers.isEmpty()) {
      return null;
    }

    lastIndex++;
    if (servers.size() <= lastIndex) {
      lastIndex = 0;
      return servers.get(0);
    }
    return servers.get(lastIndex);
  }

  //  @Override
  //  public List<RegisteredServer> choose(List<RegisteredServer> servers, int count) {
  //    List<RegisteredServer> chosenServers = new ArrayList<>();
  //    for (int i = 0; i < count; i++) {
  //      chosenServers.add(choose(servers));
  //    }
  //
  //    return chosenServers;
  //  }
}
