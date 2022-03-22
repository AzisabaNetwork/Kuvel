package net.azisaba.kuvel.loadbalancer;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;

public interface LoadBalancingStrategy {

  RegisteredServer choose(List<RegisteredServer> servers);

  //  List<RegisteredServer> choose(List<RegisteredServer> servers, int count);
}
