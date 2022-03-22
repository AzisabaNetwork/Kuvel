package net.azisaba.kuvel.loadbalancer;

import java.util.ArrayList;
import java.util.List;

public class VirtualLoadBalancerContainer {

  private final List<LoadBalancer> servers = new ArrayList<>();

  public void addServer(LoadBalancer server) {
    servers.add(server);
  }
}
