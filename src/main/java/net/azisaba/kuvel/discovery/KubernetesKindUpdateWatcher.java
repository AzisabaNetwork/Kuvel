package net.azisaba.kuvel.discovery;

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class KubernetesKindUpdateWatcher<T> implements Watcher<T> {

  private final List<Consumer<T>> updateConsumerList = new ArrayList<>();

  @Override
  public void eventReceived(Action action, T item) {
    updateConsumerList.forEach(
        consumer -> {
          try {
            consumer.accept(item);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        });
  }

  @Override
  public void onClose(WatcherException e) {
    e.printStackTrace();
  }

  public void registerPodUpdateConsumer(Consumer<T> consumer) {
    updateConsumerList.add(consumer);
  }
}
