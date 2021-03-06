`mqttx` 基于 [mqtt v3.1.1](http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html) 官方协议文档开发。
项目运行的方式：
  1. 使用`springboot`推荐的启动方式 `java -jar app.jar`，使用 `mvn clean package` 打包，这种方式需要修改配置文件中 redis 地址和端口。
  2. 基于 `docker` 容器化部署，这个就比较简单，具体的步骤见 **容器化部署**

## 容器化部署

为了方便项目快速的跑起来，引进了docker来方便项目的部署

> 执行本地部署动作前，需要先下载docker

1. 通过IDE提供的打包功能将项目打包为 target/*.jar
2. 进入 dockerfile 同级目录，执行 `docker build -t mqttx:v0.1 .`
3. 执行 docker-compose up

## 功能说明

#### 1、 qos 支持

| qos0 | qos1 | qos2 |
| ---- | ---- | ---- |
| 支持 | 支持 | 支持 |

为支持 qos1、qos2，引入 `redis` 作为持久层，这部分已经封装成接口，可自行替换实现（比如采用 `mysql`）。

#### 2、topicFilter 支持

1. 支持多级通配符 `#`与单级通配符 `+`，不支持通配符 `$`
2. 不支持以 `/` 开头的topic，比如 /a/b 会被判定为非法 topic，请改为 a/b。
3. 不支持以 `/`结尾的topic，比如 a/b/，请改为 a/b。
4. 其它规则见 [mqtt v3.1.1](http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html) 4.7 Topic Names and Topic Filters

> ps：实际上 **mqttx** 仅对订阅 topicFilter 进行校验，publish 的 topic 是没有做合法性检查的。

举例：

| topicFilter | match topics       |
| ----------- | ------------------ |
| a/b/+       | a/b/abc            |
| a/b/#       | a/b/abc, a/b/c/def |
| a/+/b/#     | a/nani/b/abc       |
| +/a/b/+/c   | aaa/a/b/test/c     |

校验工具类为：`com.jun.mqttx.utils.TopicUtis`

#### 3、集群支持

项目引入 `redis pub/sub ` 分发消息以支持集群功能。如果需要修改为 `kafka` 或其它 mq ，需要修改配置类 `ClusterConfig` 及替换实现类 `InternalMessageServiceImpl`。

> application.yml 中的 biz.enable-cluster 为集群功能开关，默认**关**

#### 4、ssl支持

开启 ssl 你首先应该有了ca，然后修改 `application.yml` 文件中几个配置：

1. `biz.key-store-location`: 证书地址，基于 `classpath`
2. `biz.ssl-enable`: 功能开关，默认 `false`
3. `biz.key-store-password`: 证书密码



### 路线图

基于我个人的认知，`mqttx` 接下来可能的开发计划：

1. 考虑整合 `SpringCloud` 
2. bug fix and optimization
3. mqtt v5 支持？？？



如果你对 **mqttx** 项目有更好的意见或建议，可以通过邮箱联系我：85998282@qq.com
