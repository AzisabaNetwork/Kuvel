package net.azisaba.kuvel.discovery;

public interface LoadBalancerDiscovery {

  void start();

  void shutdown();

  void registerLoadBalancersForStartup();
}
