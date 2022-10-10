package net.azisaba.kuvel.discovery.diffchecker;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class PodDiffChecker {

  private final HashMap<String, Pod> podMap = new HashMap<>();
  private final List<BiFunction<Pod, Pod, Boolean>> comparators = new ArrayList<>();

  public PodDiffChecker init() {
    comparators.clear();

    comparators.add(
        (pod1, pod2) -> Objects.equals(pod1.getStatus().getPhase(), pod2.getStatus().getPhase()));
    return this;
  }

  public boolean diff(Pod pod) {
    String uid = pod.getMetadata().getUid();

    if (!podMap.containsKey(uid)) {
      podMap.put(uid, pod);
      return true;
    }

    Pod oldPod = podMap.get(uid);

    for (BiFunction<Pod, Pod, Boolean> comparator : comparators) {
      boolean equal = comparator.apply(oldPod, pod);
      if (!equal) {
        podMap.put(uid, pod);
        return true;
      }
    }

    return false;
  }

  public List<String> getDeletedPodUidList(KubernetesClient client) {
    List<String> uidList = new ArrayList<>(podMap.keySet());
    client.pods().list().getItems().forEach(pod -> uidList.remove(pod.getMetadata().getUid()));

    for (String uid : uidList) {
      podMap.remove(uid);
    }

    return uidList;
  }
}
