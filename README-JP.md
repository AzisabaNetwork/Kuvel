# Kuvel

## 概要

Kubernetesクラスター内のMinecraftサーバーを監視し、Velocityに自動で反映するVelocity Plugin

## 機能

* Kubernetesクラスターの中にあるMinecraftのPodを監視し、自動でVelocityに登録/登録解除する
* LoadBalancerサーバーを作成し、そのサーバーに参加しようとしたプレイヤーを配下のサーバーに振り分ける
* Redisを使用して、複数のVelocityで名前を同期する

## 導入

Pluginは [Releases](https://github.com/AzisabaNetwork/Kuvel/releases/latest)
からダウンロードできます。 `Kuvel.jar` をダウンロードしVelocityに導入してください。ダウンロード後、コンフィグの設定を行ってください。

```yml
redis:
  group-name: "develop" # Redisサーバーが同じかつgroup-nameが同じサーバー間でのみ名前同期が行われます
  connection:
    hostname: "redis"
    port: 6379
    username: "default"
    password: "password"

# label-selectors は登録するPodとReplicaSetを指定するために使用します
label-selectors:
  - "kuvel.azisaba.net/enable-server-discovery=true"
```

Kuvelがサーバーを監視するためには、Kubernetesに対して権限を要求しなければなりません。VelocityのPodに対してPodとReplicaSetのget/list/watchを許可してください

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
# VelocityのPodに対して ServiceAccount を設定する
apiVersion: apps/v1
kind: ...
# 略
spec:
  serviceAccountName: velocity-account
# 略
 ```

## MinecraftサーバーのServiceDiscoveryを設定する

PodがMinecraftサーバーであることをKuvelに示すには、Kubernetesのラベル機能を使用します  
labelはconfigで指定します。デフォルトでは、`kuvel.azisaba.net/enable-server-discovery` の値が `true`
となっているPodとReplicaSetが登録されます。

また、一部の機能を使用するために以下のlabelを使用します。

|                 Label名                  |          値          |
|:---------------------------------------:|:-------------------:|
| kuvel.azisaba.net/preferred-server-name | Velocityに登録したいサーバー名 |
|    kuvel.azisaba.net/initial-server     |    true / false     |

### Podの場合

```yml
apiVersion: v1
kind: Pod
metadata:
  name: test-server
  labels:
    kuvel.azisaba.net/enable-server-discovery: "true" # KuvelがMinecraftサーバーを見つけるために必要 (Configに依存)
    kuvel.azisaba.net/preferred-server-name: "test-server" # Kuvelがサーバーの命名をするために必要
spec:
  containers:
    - name: test-server
      image: itzg/minecraft-server:java8
      ports:
        - containerPort: 25565
```

### Deploymentの場合
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
        kuvel.azisaba.net/enable-server-discovery: "true" # KuvelがMinecraftサーバーを見つけるために必要 (Configに依存)
        kuvel.azisaba.net/preferred-server-name: "test-server" # Kuvelがサーバーの命名をするために必要
    spec:
      containers:
        - name: test-server
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

どちらも`test-server` という名前でサーバーが登録されます。

ただし、同じ名前が指定されたサーバーが2つ以上ある場合は、サーバー名の後ろに番号が付与されていきます。具体的にはサーバー名が `test-server` と指定されたPodが2つある場合、片方は `test-server`、もう片方は `test-server-1` となります。

## ロードバランサー
Lobby等の並列化可能なサーバーにおいて、出来る限り人数を分散したいことがあります。その時にKuvelのLoadBalancer機能が役に立ちます

```yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lobby-deployment
  labels:
    kuvel.azisaba.net/enable-server-discovery: "true"
    kuvel.azisaba.net/preferred-server-name: "lobby"
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
    spec:
      containers:
        - name: lobby
          image: itzg/minecraft-server:java8
          ports:
            - containerPort: 25565
```

Deploymentに対してLabelを適用することで、KuvelのLoadBalancer機能を有効化することができます。KuvelのLoadBalancerは以下の機能を有しています

1. ReplicaSet配下のPodに対し、ランダムに接続先を振り分けて転送する
2. KubernetesのReplicaSetと同期し、自動で転送先を登録/登録解除する

これを用いることにより、`/server lobby` を実行したときに `lobby-1`, `lobby-2`, `lobby-3`の中からランダムに接続するといった仕組みを実装できます

## 複数Velocityでサーバー名を同期する

Kubernetesクラスター内ではPodがほぼ同時に作成されることがある等の理由により、まれにVelocityによってサーバーの登録名が違うといった事が起こりえます。Velocityを並列化している環境では、この現象は致命的な問題を引き起こします。Kuvelはそれを回避するため、Redisによるサーバー名同期を実現しています。Kuvelはキー名が `kuvel:`
から始まるキーを使用します。

1.xではこの機能の有効化は任意でしたが、2.0.0以降デフォルトで有効になりました。

## ライセンス

[GNU General Public License v3.0](LICENSE)