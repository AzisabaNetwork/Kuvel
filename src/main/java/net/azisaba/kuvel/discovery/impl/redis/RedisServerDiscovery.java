package net.azisaba.kuvel.discovery.impl.redis;

import com.velocitypowered.api.scheduler.ScheduledTask;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import net.azisaba.kuvel.Kuvel;
import net.azisaba.kuvel.KuvelServiceHandler;
import net.azisaba.kuvel.discovery.ServerDiscovery;
import net.azisaba.kuvel.discovery.diffchecker.PodDiffChecker;
import net.azisaba.kuvel.redis.RedisConnectionLeader;
import net.azisaba.kuvel.redis.RedisKeys;
import net.azisaba.kuvel.util.LabelKeys;
import org.apache.commons.lang3.time.DateFormatUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@RequiredArgsConstructor
public class RedisServerDiscovery implements ServerDiscovery {

  private final KubernetesClient client;
  private final Kuvel plugin;
  private final JedisPool jedisPool;
  private final String groupName;
  private final RedisConnectionLeader redisConnectionLeader;
  private final KuvelServiceHandler kuvelServiceHandler;

  private final AtomicReference<ScheduledTask> taskReference = new AtomicReference<>();
  private final PodDiffChecker podDiffChecker = new PodDiffChecker().init();
  private final ReentrantLock lock = new ReentrantLock();

  @Override
  public void start() {
    if (!redisConnectionLeader.isLeader()) {
      return;
    }

    Runnable runnable =
        () -> {
          FilterWatchListDeletable<Pod, PodList, PodResource> request = client.pods()
              .inAnyNamespace();

          for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
            request = request.withLabel(e.getKey(), e.getValue());
          }

          List<Pod> podList = request.list().getItems();

          for (Pod pod : podList) {
            if (podDiffChecker.diff(pod)) {
              processUpdatedPod(pod);
            }
          }

          List<String> uidList = podDiffChecker.getDeletedPodUidList(client);
          uidList.forEach(this::unregisterPodOrIgnore);
        };

    taskReference.getAndUpdate(
        task -> {
          if (task != null) {
            task.cancel();
          }

          return plugin
              .getProxy()
              .getScheduler()
              .buildTask(plugin, runnable)
              .repeat(5, TimeUnit.SECONDS)
              .schedule();
        });
  }

  @Override
  public void shutdown() {
    taskReference.getAndUpdate(
        task -> {
          if (task != null) {
            task.cancel();
          }
          return null;
        });
  }

  @Override
  public HashMap<String, Pod> getServersForStartup() {
    Map<String, String> podIdToServerNameMap;
    if (redisConnectionLeader.isLeader()) {
      try (Jedis jedis = jedisPool.getResource()) {
        podIdToServerNameMap = new HashMap<>(jedis.hgetAll(RedisKeys.SERVERS_PREFIX + groupName));

        for (String podUid : new ArrayList<>(podIdToServerNameMap.keySet())) {
          if (getPodByUid(podUid) == null) {
            podIdToServerNameMap.remove(podUid);
            jedis.hdel(RedisKeys.SERVERS_PREFIX + groupName, podUid);
            redisConnectionLeader.publishDeletedServer(podUid);
          }
        }

        Map<String, String> loadBalancerMap =
            jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);

        FilterWatchListDeletable<Pod, PodList, PodResource> request = client.pods()
            .inAnyNamespace();

        for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
          request = request.withLabel(e.getKey(), e.getValue());
        }

        request
            .withField("status.phase", "Running")
            .list()
            .getItems()
            .forEach(
                pod -> {
                  String uid = pod.getMetadata().getUid();
                  if (podIdToServerNameMap.containsKey(uid)) {
                    return;
                  }

                  String preferServerName =
                      pod.getMetadata()
                          .getLabels()
                          .getOrDefault(
                              LabelKeys.PREFERRED_SERVER_NAME.getKey(),
                              pod.getMetadata().getName());
                  String serverName =
                      getValidServerName(
                          preferServerName,
                          (name) ->
                              !podIdToServerNameMap.containsValue(name)
                                  && !loadBalancerMap.containsValue(name)
                                  && plugin.getProxy().getServer(name).isEmpty());

                  podIdToServerNameMap.put(uid, serverName);
                  jedis.hset(RedisKeys.SERVERS_PREFIX + groupName, uid, serverName);
                });
      }
    } else {
      try (Jedis jedis = jedisPool.getResource()) {
        podIdToServerNameMap = jedis.hgetAll(RedisKeys.SERVERS_PREFIX + groupName);
      }
    }

    String verb = "Fetched";
    if (redisConnectionLeader.isLeader()) {
      verb = "Found";
    }

    for (String podUid : podIdToServerNameMap.keySet()) {
      plugin
          .getLogger()
          .info(verb + " server: " + podIdToServerNameMap.get(podUid) + " (" + podUid + ")");
    }

    HashMap<String, Pod> servers = new HashMap<>();
    for (Entry<String, String> entry : podIdToServerNameMap.entrySet()) {
      Pod pod = getPodByUid(entry.getKey());
      if (pod == null) {
        continue;
      }

      servers.put(entry.getValue(), pod);
      kuvelServiceHandler.getPodUidAndServerNameMap().register(entry.getKey(), entry.getValue());
    }
    return servers;
  }

  private void processUpdatedPod(Pod pod) {
    lock.lock();
    try {
      if (pod.getStatus().getPhase().equalsIgnoreCase("Running")) {
        registerPodOrIgnore(pod);
      } else if (pod.getStatus().getPhase().equalsIgnoreCase("Terminating")) {
        if (pod.getMetadata().getDeletionTimestamp() == null) {
          return;
        }

        try {
          Date deletionEndDate =
              DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.parse(
                  pod.getMetadata().getDeletionTimestamp());
          Date now = new Date();
          long secondsRemaining = deletionEndDate.getTime() - now.getTime();

          if (secondsRemaining < 50) {
            unregisterPodOrIgnore(pod);
          } else {
            plugin
                .getProxy()
                .getScheduler()
                .buildTask(
                    plugin,
                    () -> {
                      Pod p = getPodByUid(pod.getMetadata().getUid());
                      if (p != null) {
                        processUpdatedPod(p);
                      }
                    })
                .delay(secondsRemaining - 49, TimeUnit.SECONDS)
                .schedule();
          }
        } catch (ParseException e) {
          e.printStackTrace();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void registerPodOrIgnore(Pod pod) {
    String uid = pod.getMetadata().getUid();
    if (kuvelServiceHandler.getPodUidAndServerNameMap().getServerNameFromUid(uid) != null) {
      return;
    }

    String serverName;

    try (Jedis jedis = jedisPool.getResource()) {
      Map<String, String> serverMap = jedis.hgetAll(RedisKeys.SERVERS_PREFIX.getKey() + groupName);
      Map<String, String> loadBalancerMap =
          jedis.hgetAll(RedisKeys.LOAD_BALANCERS_PREFIX.getKey() + groupName);

      String preferServerName =
          pod.getMetadata()
              .getLabels()
              .getOrDefault(LabelKeys.PREFERRED_SERVER_NAME.getKey(), pod.getMetadata().getName());

      serverName =
          getValidServerName(
              preferServerName,
              (name) ->
                  !serverMap.containsValue(name)
                      && !loadBalancerMap.containsValue(name)
                      && plugin.getProxy().getServer(name).isEmpty());

      kuvelServiceHandler.getPodUidAndServerNameMap().register(uid, serverName);

      boolean success = false;
      try {
        success = kuvelServiceHandler.registerPod(pod, serverName);
        if (success) {
          redisConnectionLeader.publishNewServer(uid, serverName);
          jedis.hset(RedisKeys.SERVERS_PREFIX.getKey() + groupName, uid, serverName);
        }
      } finally {
        if (!success) {
          kuvelServiceHandler.getPodUidAndServerNameMap().unregister(uid);
        }
      }
    }
  }

  private void unregisterPodOrIgnore(Pod pod) {
    unregisterPodOrIgnore(pod.getMetadata().getUid());
  }

  public void unregisterPodOrIgnore(String uid) {
    if (kuvelServiceHandler.getPodUidAndServerNameMap().getServerNameFromUid(uid) == null) {
      return;
    }

    kuvelServiceHandler.unregisterPod(uid);
    // podUidAndServerNameMap.unregister(uid); // no need
    redisConnectionLeader.publishDeletedServer(uid);

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.hdel(RedisKeys.SERVERS_PREFIX.getKey() + groupName, uid);
    }
  }

  private Pod getPodByUid(String podUid) {
    FilterWatchListDeletable<Pod, PodList, PodResource> request = client.pods()
        .inAnyNamespace();

    for (Entry<String, String> e : plugin.getKuvelConfig().getLabelSelectors().entrySet()) {
      request = request.withLabel(e.getKey(), e.getValue());
    }

    return request.list().getItems().stream()
        .filter(pod -> pod.getMetadata().getUid().equals(podUid))
        .findFirst()
        .orElse(null);
  }

  private String getValidServerName(String prefer, Function<String, Boolean> isValid) {
    if (isValid.apply(prefer)) {
      return prefer;
    }

    String name = prefer + "-1";
    int i = 1;
    while (!isValid.apply(name)) {
      name = name.substring(0, name.length() - (1 + String.valueOf(i).length())) + "-" + (i + 1);
      i++;
    }
    return name;
  }

  private Runnable getDelayRunnable(ExecutorService executor, Runnable runnable) {
    return () -> {
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      try {
        runnable.run();
      } catch (Exception ex) {
        ex.printStackTrace();
      } finally {
        executor.submit(getDelayRunnable(executor, runnable));
      }
    };
  }
}
