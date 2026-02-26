package net.azisaba.kuvel.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.Map;
import java.util.TreeMap;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.kyori.adventure.text.Component;

public class KuvelCommand implements SimpleCommand {

  private final Kuvel plugin;

  public KuvelCommand(Kuvel plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    if (!source.hasPermission("kuvel.command")) {
      source.sendMessage(Component.text("You don't have permission to use this command."));
      return;
    }

    KuvelServiceHandler handler = plugin.getKuvelServiceHandler();
    if (handler == null) {
      source.sendMessage(Component.text("Kuvel is not initialized."));
      return;
    }

    String[] args = invocation.arguments();
    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      sendHelp(source);
      return;
    }

    if (args[0].equalsIgnoreCase("status")) {
      Map<String, String> podMap = handler.getPodUidAndServerNameMap().getAllMap();
      Map<String, String> replicaSetMap = handler.getReplicaSetUidAndServerNameMap().getAllMap();

      long missingPodRegistrations =
          podMap.values().stream().filter(name -> plugin.getProxy().getServer(name).isEmpty()).count();
      long missingLoadBalancerRegistrations =
          replicaSetMap.values().stream()
              .filter(name -> plugin.getProxy().getServer(name).isEmpty())
              .count();

      source.sendMessage(
          Component.text(
              "Kuvel status: leader="
                  + (plugin.getRedisConnectionLeader() != null
                      && plugin.getRedisConnectionLeader().isLeader())
                  + ", podMappings="
                  + podMap.size()
                  + ", loadBalancerMappings="
                  + replicaSetMap.size()
                  + ", initialServers="
                  + handler.getInitialServerNames().size()));
      source.sendMessage(
          Component.text(
              "Missing registrations: pods="
                  + missingPodRegistrations
                  + ", loadBalancers="
                  + missingLoadBalancerRegistrations));
      return;
    }

    if (args[0].equalsIgnoreCase("list")) {
      if (args.length < 2) {
        source.sendMessage(Component.text("Usage: /kuvel list <pods|loadbalancers>"));
        return;
      }

      if (args[1].equalsIgnoreCase("pods")) {
        TreeMap<String, String> map = new TreeMap<>(handler.getPodUidAndServerNameMap().getAllMap());
        source.sendMessage(Component.text("Registered pod mappings: " + map.size()));
        map.forEach((uid, serverName) -> source.sendMessage(Component.text("- " + uid + " -> " + serverName)));
        return;
      }

      if (args[1].equalsIgnoreCase("loadbalancers")) {
        TreeMap<String, String> map =
            new TreeMap<>(handler.getReplicaSetUidAndServerNameMap().getAllMap());
        source.sendMessage(Component.text("Registered load balancer mappings: " + map.size()));
        map.forEach((uid, serverName) -> source.sendMessage(Component.text("- " + uid + " -> " + serverName)));
        return;
      }

      source.sendMessage(Component.text("Usage: /kuvel list <pods|loadbalancers>"));
      return;
    }

    if (args[0].equalsIgnoreCase("register")) {
      if (args.length < 3) {
        source.sendMessage(Component.text("Usage: /kuvel register <podUid> <serverName>"));
        return;
      }
      if (plugin.getProxy().getServer(args[2]).isPresent()) {
        source.sendMessage(Component.text("Server name is already registered: " + args[2]));
        return;
      }

      handler.registerPod(args[1], args[2]);
      if (args[2].equals(handler.getPodUidAndServerNameMap().getServerNameFromUid(args[1]))) {
        source.sendMessage(Component.text("Registered pod mapping: " + args[1] + " -> " + args[2]));
      } else {
        source.sendMessage(Component.text("Failed to register pod mapping."));
      }
      return;
    }

    if (args[0].equalsIgnoreCase("unregister")) {
      if (args.length < 2) {
        source.sendMessage(Component.text("Usage: /kuvel unregister <podUid>"));
        return;
      }

      if (!handler.isPodRegistered(args[1])) {
        source.sendMessage(Component.text("Pod uid is not currently registered: " + args[1]));
        return;
      }

      handler.unregisterPod(args[1]);
      source.sendMessage(Component.text("Unregistered pod mapping: " + args[1]));
      return;
    }

    if (args[0].equalsIgnoreCase("setname")) {
      if (args.length < 3) {
        source.sendMessage(Component.text("Usage: /kuvel setname <podUid> <serverName>"));
        return;
      }
      if (plugin.getProxy().getServer(args[2]).isPresent()) {
        source.sendMessage(Component.text("Server name is already registered: " + args[2]));
        return;
      }

      String oldName = handler.getPodUidAndServerNameMap().getServerNameFromUid(args[1]);
      if (oldName != null) {
        handler.unregisterPod(args[1]);
      }

      handler.registerPod(args[1], args[2]);
      if (args[2].equals(handler.getPodUidAndServerNameMap().getServerNameFromUid(args[1]))) {
        source.sendMessage(
            Component.text(
                "Updated pod mapping: " + args[1] + " -> " + args[2] + " (was " + oldName + ")"));
      } else {
        source.sendMessage(Component.text("Failed to update pod mapping."));
      }
      return;
    }

    if (args[0].equalsIgnoreCase("repair")) {
      int repairedPod = 0;
      int cleanedPod = 0;
      for (Map.Entry<String, String> entry : handler.getPodUidAndServerNameMap().getAllMap().entrySet()) {
        if (plugin.getProxy().getServer(entry.getValue()).isPresent()) {
          continue;
        }
        handler.registerPod(entry.getKey(), entry.getValue());
        if (plugin.getProxy().getServer(entry.getValue()).isPresent()) {
          repairedPod++;
        } else {
          handler.unregisterPod(entry.getKey());
          cleanedPod++;
        }
      }

      int cleanedLoadBalancer = 0;
      for (Map.Entry<String, String> entry :
          handler.getReplicaSetUidAndServerNameMap().getAllMap().entrySet()) {
        if (plugin.getProxy().getServer(entry.getValue()).isPresent()) {
          continue;
        }
        handler.unregisterLoadBalancer(entry.getKey());
        cleanedLoadBalancer++;
      }

      source.sendMessage(
          Component.text(
              "Repair complete: repairedPod="
                  + repairedPod
                  + ", cleanedPod="
                  + cleanedPod
                  + ", cleanedLoadBalancer="
                  + cleanedLoadBalancer));
      return;
    }

    sendHelp(source);
  }

  private void sendHelp(CommandSource source) {
    source.sendMessage(Component.text("/kuvel status"));
    source.sendMessage(Component.text("/kuvel list <pods|loadbalancers>"));
    source.sendMessage(Component.text("/kuvel register <podUid> <serverName>"));
    source.sendMessage(Component.text("/kuvel unregister <podUid>"));
    source.sendMessage(Component.text("/kuvel setname <podUid> <serverName>"));
    source.sendMessage(Component.text("/kuvel repair"));
  }
}
