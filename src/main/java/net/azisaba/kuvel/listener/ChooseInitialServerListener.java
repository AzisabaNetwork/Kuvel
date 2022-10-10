package net.azisaba.kuvel.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.KuvelServiceHandler;

@RequiredArgsConstructor
public class ChooseInitialServerListener {

  private final ProxyServer proxy;
  private final KuvelServiceHandler handler;

  @Subscribe
  public void onInitialServerChoose(PlayerChooseInitialServerEvent event) {
    if (handler.getInitialServerNames().isEmpty()) {
      return;
    }

    List<String> initialServerNames = new ArrayList<>(handler.getInitialServerNames());
    Collections.shuffle(initialServerNames);

    RegisteredServer server = null;
    for (String initialServerName : initialServerNames) {
      Optional<RegisteredServer> optionalServer = proxy.getServer(initialServerName);
      if (optionalServer.isPresent()) {
        server = optionalServer.get();
        break;
      }
    }

    if (server == null) {
      return;
    }

    event.setInitialServer(server);
  }
}
