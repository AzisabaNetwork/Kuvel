# Kuvel
## Overview
Kuvel, A service discovery plugin for Velocity. It will automatically discover Minecraft servers and register/unregister them in Velocity.

## Features

* Monitor Minecraft pods in a Kubernetes cluster and automatically register/unregister them with Velocity
* Create a LoadBalancer server and distribute players trying to join to the linked servers.
* Synchronize server names across multiple Velocity servers using Redis

## Installing

The Plugin can be downloaded from [Releases](https://github.com/AzisabaNetwork/Kuvel/releases/latest). Download `Kuvel.jar` and install it into Velocity.

In order for Kuvel to monitor the server, you must request permission from Kubernetes to allow Velocity pods discovery Minecraft servers. For Velocity pods, please allow get/list/watch to Pods and ReplicaSets.
```yml
 apiVersion: v1
 kind: ServiceAccount
 metadata:
   name: velocity-account
   namespace: default
 ---
 apiVersion: rbac.authorization.k8s.io/v1
 kind: ClusterRoleBinding
 metadata:
   name: velocity-clusterrolebiding
 roleRef:
   apiGroup: rbac.authorization.k8s.io
   kind: ClusterRole
   name: view
 subjects:
 - kind: ServiceAccount
   name: velocity-account
   namespace: default
 ```
 ```yml
# Apply ServiceAccount to the Velocity pod
apiVersion: apps/v1
kind: ...
# ...
spec:
  serviceAccountName: velocity-account
# ...
 ```

## Enable Service Discovery on the Minecraft Servers
To tell Kuvel that the pod is a Minecraft server, use Label feature of Kubernetes.
|Label Name| Value |
|:---:|:---:|
|minecraftServiceDiscovery|true / false|
|minecraftServerName|Name of the server you wish to register with Velocity|

### Pod
```yml
apiVersion: v1
kind: Pod
metadata:
  name: test-server
  labels:
    minecraftServiceDiscovery: "true" # Required for Kuvel to detect Minecraft servers.
    minecraftServerName: "test-server" # Required for Kuvel to name the server
spec:
  containers:
  - name: test-server
    image: itzg/minecraft-server:java8
    ports:
    - containerPort: 25565
```

### Deployment
```yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-server-deployment
spec:
  replicas: 2
  selector:
    matchLabels:
      app: test-server-deployment
  template:
    metadata:
      labels:
        app: test-server-deployment
        minecraftServiceDiscovery: "true" # Required for Kuvel to detect Minecraft servers.
        minecraftServerName: "test-server" # Required for Kuvel to name the server
    spec:
      containers:
        - name: test-server
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

In both cases, the server is registered under the name `test-server`.

However, if there are two or more servers with the same name, a number will be assigned after the server name. Specifically, if there are two pods with the server name `test-server`, one will be `test-server` and the other will be `test-server-1`.

## Load Balancer

On parallelizable servers such as Lobby, it is sometimes desirable to distribute the number of players as much as possible. This is where Kuvel's LoadBalancer feature comes in handy.

```yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lobby-deployment
  labels:
    minecraftServiceDiscovery: "true"
    minecraftServerName: "lobby"
spec:
  replicas: 3
  selector:
    matchLabels:
      app: lobby-deployment
  template:
    metadata:
      labels:
        app: lobby-deployment
        minecraftServiceDiscovery: "true"
        minecraftServerName: "lobby"
    spec:
      containers:
        - name: lobby
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

By applying a Label to a Deployment, Kuvel's LoadBalancer feature can be activated.

1. Distribute players who try to join the Load Balancer server to the pods under ReplicaSet.
2. Synchronize with Kubernetes ReplicaSet and automatically register/unregister forwarding destinations

Using this, you can implement a mechanism to randomly connect to `lobby-1`, `lobby-2`, or `lobby-3` when `/server lobby` is invoked.

## Synchronize Server Names in Multi Velocity Environment

In a Kubernetes cluster, pods can be created at almost the same time, and this can cause a fatal problem in a parallel Velocity environment because it is possible that different Velocity servers have different registration names. Kuvel provides name synchronization using Redis to avoid this problem.

To enable this feature, simply set `redis.enable` to `true` in config.yml. Kuvel uses keys whose key name begins with `kuvel:`.
```yml
redis:
  enable: true # Setting it to true enables server name synchronization using Redis
  group-name: "production" # Name synchronization is performed only between servers with the same Redis server and the same group-name
  connection:
    hostname: "localhost"
    port: 6379
    username: "root"
    password: "password"
```

## License
[GNU General Public License v3.0](LICENSE)