package net.azisaba.kuvel.util;

import java.util.HashMap;
import java.util.Map;

public class UidAndServerNameMap {

  private final HashMap<String, String> uidToServerName = new HashMap<>();

  public String getServerNameFromUid(String podUid) {
    return uidToServerName.get(podUid);
  }

  public String getUidFromServerName(String serverName) {
    for (String podUid : uidToServerName.keySet()) {
      if (uidToServerName.get(podUid).equals(serverName)) {
        return podUid;
      }
    }
    return null;
  }

  public Map<String, String> getAllMap() {
    return new HashMap<>(uidToServerName);
  }

  public void register(String uid, String serverName) {
    uidToServerName.put(uid, serverName);
  }

  public String unregister(String uid) {
    return uidToServerName.remove(uid);
  }
}
