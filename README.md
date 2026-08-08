# RaidCore — Escape from Craftov · 逃离块科夫

Minecraft Paper 服务端的「搜打撤 / Extraction Shooter」核心框架。一套代码编译出 **两个插件**:

| 插件 | Jar | 职责 |
|---|---|---|
| **RaidCoreGame** | `game/build/libs/RaidCoreGame-<version>.jar` | 游戏服务端:单服 N 个对局实例、每实例独立世界、聊天/Tab 按实例隔离、Redis 实例目录心跳、消费匹配分配、自动补备实例 |
| **RaidCoreLobby** | `lobby/build/libs/RaidCoreLobby-<version>.jar` | 大厅:Redis 匹配队列(填桶优先 + 席位预留防超卖)、玩家设置、装扮、Hypixel 风格菜单 |

```
Velocity / BungeeCord 代理
   │
   ├── 大厅服务端      ← RaidCoreLobby.jar ─┐
   ├── 游戏服务端 #1   ← RaidCoreGame.jar  ─┤
   └── 游戏服务端 #2   ← RaidCoreGame.jar  ─┴─→ Redis(实例目录 / 匹配分配 / 扩容提示)
                                               PostgreSQL(玩家档案,仅游戏服务端)
```

## Requirements

- **Java 25**(Temurin 推荐)
- **Paper 1.21.11**
- **Redis**(两个插件都需要;大厅与游戏服务端必须指向同一个 Redis)
- **PostgreSQL**(仅游戏服务端;强依赖 —— 连不上会中止启动。本项目不提供 memory 等替代模式)
- Gradle Wrapper 已内置(Gradle 9.7.0),无需本机安装 Gradle

## Build

```bash
./gradlew clean build
```

- 产物:`game/build/libs/RaidCoreGame-0.1.0-SNAPSHOT.jar` 与 `lobby/build/libs/RaidCoreLobby-0.1.0-SNAPSHOT.jar`
- 只出插件 Jar:`./gradlew :game:buildPlugin :lobby:buildPlugin`
- 启动开发服务器(Paper 1.21.11,自动挂载 RaidCoreGame):`./gradlew :game:runServer`

## Architecture(4 模块,依赖单向)

```
api  ──►  core  ──►  game(插件①)
                └──►  lobby(插件②)
```

| 模块 | 包 | 规则 |
|---|---|---|
| `api` | `com.birditch.raidcore.common` + `com.birditch.raidcore.api` | 纯契约层:接口、record 值对象、领域事件、通用工具。**编译期零 Paper 依赖**,其它插件理论上只依赖它即可与 RaidCore 交互;附 testFixtures(`ManualScheduler`/`MutableTimeSource`) |
| `core` | `com.birditch.raidcore.core`(运行时)+ 7 个领域包(`item/loot/profile/economy/combat/game/extraction`)+ `redis` + `bukkit` | 唯一共享实现库。**硬规则:只有 `bukkit` 包允许 import Paper API**;领域包之间不互相 import,只通过 api 接口经 `ServiceRegistry` 协作;Jedis/Gson 类型不出 `redis` 包 |
| `game` | `com.birditch.raidcore.game.plugin`(+ `world/instance/isolation/network/command/listener`) | 薄启动层 + 游戏服务端特性 |
| `lobby` | `com.birditch.raidcore.lobby`(+ `matchmaking/menu/command/listener`) | 薄启动层 + 大厅特性;不使用数据库 |

**线程模型**:所有领域状态主线程限定;仓储 IO 走虚拟线程执行器;异步回调经 `RaidScheduler.runSync` 跳回主线程后才碰游戏状态;Redis 订阅在独立虚拟线程,处理器负责跳线程。

**领域生命周期**:`RaidGameState` 状态机 `CREATED → WAITING → STARTING → RUNNING → ENDING → FINISHED → DESTROYED`(非法迁移抛 `GameStateException`);模块生命周期由 `ModuleManager` 按注册顺序 init/start、逆序 stop。

## Redis 键与协议

| 键 / 频道 | 写 | 读 | 说明 |
|---|---|---|---|
| `raidcore:servers:<serverId>` | 游戏服 | 大厅 | 实例目录快照 JSON,TTL = 3×发布间隔(心跳) |
| `raidcore:pending:<playerUuid>` | 大厅 | 游戏服(GETDEL) | 匹配分配,玩家跨服到达后原子消费 |
| `raidcore:provision`(pub/sub) | 大厅 | 游戏服 | 队列饥饿时的扩容提示(限流) |
| `raidcore:settings:<uuid>` / `raidcore:cosmetics:<uuid>` | 大厅 | 大厅 | 玩家设置(hash)与装扮 |

## 配置

每个插件的数据目录会生成带**详尽中文注释**的配置(所有键的含义/类型/取值范围见文件内文档):

- 两插件:`config.yml`、`redis.yml`、`messages.yml`(MiniMessage,全中文,Hypixel 风格)
- 仅游戏服务端:`database.yml`(PostgreSQL 连接;表 `raid_profiles` 启动时自动建立)
- 游戏服关键项:`server.id`(网络内唯一)、`server.proxy-name`(代理注册名)、`game.max-instances`、`game.min-ready-instances`、`game.map-template-dir`
- 地图模板:把整份世界文件夹放到 `plugins/RaidCoreGame/maps/<地图ID>/`,实例创建时整份复制;缺失时退化为平坦世界(仅调试用)

## Commands

游戏服务端(`raid.admin` 默认 OP;`raid.command.info` 默认所有人):

```
/raid help | info | profile
/raid game create [map] | list | start <id> | stop <id>
/raid debug | debug loot <table>
```

大厅(`raid.lobby.play` 默认所有人):

```
/play [map]     加入匹配队列
/leavequeue     离开队列
/lobby, /menu   打开大厅菜单(或右键"游戏菜单"指南针)
```

## Development

- **加领域模块**:实现 `RaidModule`(`initialize` 里用构造注入装配服务并注册进 `ServiceRegistry`)→ 在 `GameApplication.createModules()` 按依赖顺序注册(当前顺序:item, loot, profile, economy, combat, game, extraction)。
- **加服务**:接口放 `api`,实现放 `core` 对应领域包,由所属模块注册;跨领域协作只通过 api 接口。
- **加物品**:`DefaultItems.registerAll` 增加 `ItemDefinition.builder(...)`(或后续接入配置化注册)。
- **加掉落表**:`DebugLootTables.registerAll` 注册 `LootTable`(池 + 权重条目,`WeightedRandom` 掷取)。
- **加撤离点类型**:实现 `ExtractionCondition`,在创建 `ExtractionPoint` 时附加条件。
- **测试**:纯 JUnit 6,不启动 Paper;用 api testFixtures(`ManualScheduler` 推进定时任务、`MutableTimeSource` 拨时钟)+ `SeededRandomProvider` 做确定性测试。`./gradlew test`。

## 技术栈

Java 25(record/sealed/模式匹配/虚拟线程/`///` 文档注释,无 preview)· Paper API 1.21.11 · Gradle 9.7 Kotlin DSL + Version Catalog · JSpecify 1.0.1(`@NullMarked`)· HikariCP 7.1.0 · Jedis 7.5.3 · Gson 2.14.0 · JUnit 6.1.3 · Shadow 9.6.1(依赖 relocate 到 `com.birditch.raidcore.libs.*`)· run-paper 3.0.2
