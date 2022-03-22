package net.azisaba.kuvel.loadbalancer.strategy.impl;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Comparator;
import java.util.List;
import net.azisaba.kuvel.loadbalancer.LoadBalancingStrategy;

public class MinimumPlayerLoadBalancingStrategy implements LoadBalancingStrategy {

  @Override
  public RegisteredServer choose(List<RegisteredServer> servers) {
    if (servers.isEmpty()) {
      return null;
    }

    return servers.stream()
        .min(Comparator.comparingInt(a -> a.getPlayersConnected().size()))
        .orElse(null);
  }

  //  @Override
  //  public List<RegisteredServer> choose(List<RegisteredServer> servers, int count) {
  //    List<RegisteredServer> chosenServers = new ArrayList<>();
  //    HashMap<String, Integer> addedPlayerCount = new HashMap<>();
  //
  //    for (int i = 0; i < count; i++) {
  //      RegisteredServer server =
  //          servers.stream()
  //              .min(
  //                  (server1, server2) -> {
  //                    int server1Count =
  //                        server1.getPlayersConnected().size()
  //                            + addedPlayerCount.getOrDefault(server1.getServerInfo().getName(),
  // 0);
  //                    int server2Count =
  //                        server2.getPlayersConnected().size()
  //                            + addedPlayerCount.getOrDefault(server2.getServerInfo().getName(),
  // 0);
  //                    return server1Count - server2Count;
  //                  })
  //              .orElse(null);
  //      if (server == null) {
  //        throw new IllegalStateException("No server found");
  //      }
  //
  //      chosenServers.add(server);
  //      addedPlayerCount.put(
  //          server.getServerInfo().getName(),
  //          addedPlayerCount.getOrDefault(server.getServerInfo().getName(), 0) + 1);
  //    }
  //
  //    return chosenServers;
  //  }
}
