# Kuvel
## Overview
Kuvel, A service discovery plugin for Velocity. It will automatically discover Minecraft servers and
register/unregister them within Velocity.

## Features

* Monitor Minecraft pods in a Kubernetes cluster and automatically register/unregister them within
  Velocity
* Create a LoadBalancer server and distribute players trying to join to the linked servers.
* Synchronize server names across multiple Velocity instances using Redis

## Installing

The Plugin can be downloaded
from [Releases](https://github.com/AzisabaNetwork/Kuvel/releases/latest). Download `Kuvel.jar` and
install it into Velocity plugins directory. The config file requires initial setup as seen below.

```yml
# Server name synchronization by Redis is required in load-balanced environments using multiple Velocity instances.
redis:
  group-name: "develop"
  connection:
    hostname: "redis"
    port: 6379
    # username is optional. if you have authentication enabled, you can use it here. Otherwise you can leave it blank or null.
    username: "default"
    # password is optional. if you have authentication enabled, you can use it here. Otherwise you can leave it blank or null.
    password: "password"

# label-selectors are used to filter Pods and ReplicaSets to be registered.
label-selectors:
  - "kuvel.azisaba.net/enable-server-discovery=true"
```

In order for Kuvel to monitor the server, you must request permission from Kubernetes. For Velocity pods, please allow get/list/watch to Pods
and ReplicaSets.

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

To tell Kuvel that the pod is a Minecraft server, use the Kubernetes label service.  
The label keys and values are specified in the config file. By default, the label key
is `kuvel.azisaba.net/enable-server-discovery` and the value is `true`.

The following labels are also used for some other features.

|               Label Name                |                         Value                         |
|:---------------------------------------:|:-----------------------------------------------------:|
| kuvel.azisaba.net/preferred-server-name | Name of the server you wish to register with Velocity |
|    kuvel.azisaba.net/initial-server     |                     true / false                      |

### Pod

```yml
apiVersion: v1
kind: Pod
metadata:
  name: test-server
  labels:
    kuvel.azisaba.net/enable-server-discovery: "true" # Required for Kuvel to detect Minecraft servers. Depends on your config.
    kuvel.azisaba.net/preferred-server-name: : "test-server" # Required for Kuvel to name the server
    # kuvel.azisaba.net/initial-server: "true" # Uncomment this line if you want to make this server the initial server.   
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
        kuvel.azisaba.net/enable-server-discovery: "true" # Required for Kuvel to detect Minecraft servers. Depends on your config.
        kuvel.azisaba.net/preferred-server-name: "test-server" # Required for Kuvel to name the server
        # kuvel.azisaba.net/initial-server: "true" # Uncomment this line if you want to make this server the initial server.
    spec:
      containers:
        - name: test-server
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

In both cases, the server is registered under the name `test-server`.

However, if there are two or more servers with the same name, a number will be assigned after the server name. For example, if there are two pods with the server name `test-server`, one will be `test-server` and the other will be `test-server-1`.

## Load Balancer

On parallelizable servers such as Lobby servers, it is sometimes desirable to distribute the number of players as evenly as possible. This is where Kuvel's LoadBalancer feature comes in handy.

```yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lobby-deployment
  labels:
    kuvel.azisaba.net/enable-server-discovery: "true"
    kuvel.azisaba.net/preferred-server-name: "lobby"
    # kuvel.azisaba.net/initial-server: "true" # Uncomment this line if you want to make this load balancer server the initial server.
spec:
  replicas: 3
  selector:
    matchLabels:
      app: lobby-deployment
  template:
    metadata:
      labels:
        app: lobby-deployment
        kuvel.azisaba.net/enable-server-discovery: "true"
        kuvel.azisaba.net/preferred-server-name: "lobby"
        # kuvel.azisaba.net/initial-server: "true" # Uncomment this line if you want to make this server the initial server.
    spec:
      containers:
        - name: lobby
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

By applying a Label to a Deployment, Kuvel's LoadBalancer feature can be activated. Kuvel's LoadBalancer has the following features.

1. Distribute players who try to join the Load Balancer server randomly to the pods under ReplicaSet.
2. Synchronize with Kubernetes ReplicaSet and automatically register/unregister forwarding destinations.

Using this, you can implement a mechanism to randomly connect to `lobby-1`, `lobby-2`, or `lobby-3` when `/server lobby` is invoked.

## Synchronize Server Names in Multi Velocity Environments

In a Kubernetes cluster, pods can be created at almost the same time, and this can cause different 
Velocity servers to have different registration names. This can cause fatal issues in a parallel 
Velocity environment. Kuvel provides name synchronization using Redis to avoid this
issue. Kuvel uses keys whose key name begins with `kuvel:`.

On 1.x, this feature was optional, but from 2.0.0, this setting is enabled by default.

## License
[GNU General Public License v3.0](LICENSE)
