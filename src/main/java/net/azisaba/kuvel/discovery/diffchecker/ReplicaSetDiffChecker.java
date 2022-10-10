package net.azisaba.kuvel.discovery.diffchecker;

import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class ReplicaSetDiffChecker {

  private final HashMap<String, ReplicaSet> replicaSetMap = new HashMap<>();
  private final List<BiFunction<ReplicaSet, ReplicaSet, Boolean>> comparators = new ArrayList<>();

  public ReplicaSetDiffChecker init() {
    comparators.clear();

    comparators.add(
        (replica1, replica2) ->
            Objects.equals(replica1.getSpec().getReplicas(), replica2.getSpec().getReplicas()));
    return this;
  }

  public boolean diff(ReplicaSet replicaSet) {
    String uid = replicaSet.getMetadata().getUid();

    if (!replicaSetMap.containsKey(uid)) {
      replicaSetMap.put(uid, replicaSet);
      return true;
    }

    ReplicaSet oldReplicaSet = replicaSetMap.get(uid);

    for (BiFunction<ReplicaSet, ReplicaSet, Boolean> comparator : comparators) {
      boolean equal = comparator.apply(oldReplicaSet, replicaSet);
      if (!equal) {
        replicaSetMap.put(uid, replicaSet);
        return true;
      }
    }

    return false;
  }

  public List<String> getDeletedReplicaSetUidList(KubernetesClient client) {
    List<String> uidList = new ArrayList<>(replicaSetMap.keySet());
    client
        .apps()
        .replicaSets()
        .list()
        .getItems()
        .forEach(pod -> uidList.remove(pod.getMetadata().getUid()));

    for (String uid : uidList) {
      replicaSetMap.remove(uid);
    }

    return uidList;
  }
}
