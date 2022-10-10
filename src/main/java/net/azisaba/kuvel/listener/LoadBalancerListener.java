package net.azisaba.kuvel.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.KuvelServiceHandler;

@RequiredArgsConstructor
public class LoadBalancerListener {

  private final KuvelServiceHandler handler;

  @Subscribe(order = PostOrder.LATE)
  public void onInitialServerChoose(PlayerChooseInitialServerEvent event) {
    event
        .getInitialServer()
        .ifPresent(
            server -> {
              String serverName = server.getServerInfo().getName();
              handler
                  .getLoadBalancer(serverName)
                  .ifPresent(
                      lb -> {
                        RegisteredServer target = lb.getTarget();
                        if (target != null) {
                          event.setInitialServer(target);
                        }
                      });
            });
  }

  @Subscribe(order = PostOrder.LATE)
  public void onServerChanged(ServerPreConnectEvent event) {
    event
        .getResult()
        .getServer()
        .ifPresent(
            server -> {
              String serverName = server.getServerInfo().getName();
              handler
                  .getLoadBalancer(serverName)
                  .ifPresent(
                      lb -> {
                        RegisteredServer target = lb.getTarget();
                        if (target != null) {
                          event.setResult(ServerResult.allowed(target));
                        } else {
                          event.setResult(ServerResult.denied());
                        }
                      });
            });
  }
}
