# RaidCore

RaidCore 是一个运行在 Minecraft Paper 上的「搜打撤」游戏框架，游戏名是 **Escape from Craftov / 逃离块科夫**。

完整的玩家玩法、管理员运营、代理配置、数据库闭环和开发者扩展说明见 [GAMEPLAY.md](GAMEPLAY.md)。

玩家带着装备进入独立地图，搜索物资，与怪物或其他玩家战斗，然后尝试活着撤离。找到东西还不算真正拥有；只有撤离成功，战利品才会进入你的**物资仓库**。死亡则可能失去本局携带的装备与物资。

当前版本的重点是：Game/Lobby 强制分服、PostgreSQL 玩家数据、多对局实例、Manual First 可视化地图编辑器，以及已经接通的 Loot、原生背包、撤离入库和死亡损失闭环。AI 实体、交易行等仍在开发中，本文不会把规划功能写成已完成。

## 什么是搜打撤？

你可以把每局理解成一次有风险的行动。

开始前，你决定带什么装备。进入地图后，可以搜房子、避开危险、攻击玩家，或者尽早离开。杀人不是唯一目标，活着把有价值的东西带回来才是核心目标。

举个例子：

> 你带着一把铁剑、一张弓和两瓶治疗药水进入地图。搜索废弃矿井时，你找到一本附魔书和几块回响碎片，随后听见远处传来弓弦声。你可以举盾靠近争夺，也可以绕开。背包已经很值钱时，你决定不再冒险，前往撤离点。倒计时结束并撤离成功后，这些物品才真正属于你。

## 一局游戏怎么玩？

完整玩法目标如下：

1. 在 Lobby 准备装备并选择地图。
2. Lobby 通过 Redis 找到可用 Game 实例。
3. Game 从地图模板复制一个独立世界。
4. 玩家从该局固定的出生点进入地图。
5. 玩家搜索 Loot，使用剑、斧、弓、弩、盾牌、药水和地形应对 AI 或其他玩家。
6. 玩家管理背包，决定继续冒险还是撤离。
7. 进入撤离区域并等待倒计时。
8. 成功撤离或对局结束后返回 Lobby。
9. 成功撤离后，战利品会安全送回你的物资仓库。

目前分服、匹配数据、实例世界、出生点、Loot 交互、条件撤离、原生 PvP、物品结算和返回 Lobby 已接通；AI 生物行为仍未实现。

## 游戏核心系统

### 物资仓库与背包

物资仓库保存成功带回的长期物品，出战栏决定下一局带入的物品，本局使用 Minecraft 原生背包。撤离会把实际携带的战利品送回物资仓库；死亡/退出/超时则删除本局物品。

### Loot 与物资

管理员可以创建虚拟 Loot Point 或绑定现有方块。玩家右键后打开共享搜索箱，LootTable 只在第一次打开时生成，物品拿取和公共容器状态都属于当前实例。

### Minecraft PvP

战斗完全以 Minecraft 1.21.11 的原生规则为基础：剑和斧负责近战，弓和弩负责远程压制，盾牌用于格挡，护甲与附魔改变攻防，药水、金苹果和食物负责恢复或提供状态效果。击退、暴击、投射物、火焰、摔落、岩浆和其他环境伤害都属于行动中的风险。

同一 Raid 实例中的玩家可以使用这些原生机制 PvP。RaidCore 会取消跨实例/非行动阶段伤害，按配置处理队伍友伤，解析近战和投射物击杀归属，并在死亡结算成功后返回大厅。倒地救援和耐久专属结算仍属规划。

### AI

管理员可以配置 AI Zone、数量范围、刷新时间与难度。AI 计划使用 Minecraft 生物、原生装备和药水效果构成 PvE 威胁。AI 实体、寻路和战斗行为尚未实现。

### 撤离点

玩家进入地图配置中的 Cuboid 撤离区域后开始撤离，离开区域会取消。

- `NORMAL`：直接撤离。
- `TIMED`：行动开始达到指定秒数后开放。
- `PAID`：支付金币后撤离，同一局只扣一次；扣费失败时不会吞掉物资。
- `ITEM_REQUIRED`：背包中需要带有指定 `raidcore:item_id` PDC 的物品。

每局创建时会捕获地图 revision、出生点和撤离区域。管理员之后保存地图不会热改正在运行的对局，只影响下一局。

撤离倒计时结束后，玩家会进入“物资正在安全送回”状态。此时系统已经锁定本局实际携带物的快照，并暂时阻止移动、物品操作、拾取、丢弃、交互和伤害；玩家不能离开区域后重复发起撤离，也不会因为结算中的短暂断线被同时按阵亡处理。仓库暂时不可用时会每 5 秒自动重试；只有长期仓库确认完成后，才显示“撤离成功”并返回大厅。

## 功能状态

| 功能 | 状态 | 说明 |
|---|---|---|
| Game / Lobby 强制分服 | 完成 | 两个 Jar 同服时拒绝启动 |
| PostgreSQL 档案、等级、余额 | 完成 | Hibernate ORM + HikariCP |
| PostgreSQL 设置与装扮 | 完成 | Lobby 等待数据就绪后开放菜单 |
| Redis 匹配与实例目录 | 完成 | 只保存带 TTL 的瞬态数据 |
| 多对局独立世界 | 完成 | 每局复制世界，结束后卸载并删除 |
| Manual First 地图制作 | 完成 | 导入现有世界后，由管理员在游戏内精确设置点位和区域 |
| 地图自动验证 | 完成 | 自动检查 Bounds、Spawn、Loot、AI、Extraction、POI 与引用关系 |
| 游戏内地图编辑器 | 完成 | 双点选区、个人粒子、HUD、点击选择、属性菜单、批量编辑、撤销/重做 |
| 地图验证与启用门禁 | 完成 | ERROR 阻止启用，WARNING 只提醒 |
| 地图 revision 与回滚 | 完成 | 原子保存，默认保留 3 个旧版本 |
| 对局地图快照 | 完成 | 运行中对局不会被热编辑改变 |
| WorldEdit / LuckPerms | 硬依赖 | 地图选区和权限事实源，缺失时拒绝加载 Game |
| DecentHolograms / PlaceholderAPI | 硬依赖 | 编辑器标签和对外占位符，缺失或适配器异常时拒绝加载 Game |
| TrMenu 社区版 3.12.5 | 硬依赖 | Lobby/Game 全部 Inventory GUI；动态物品由 RaidCore 数据源注入 |
| 虚拟 Loot 搜索与撤离结算 | 完成 | 共享一次性 Loot GUI，成功撤离后入仓 |
| AI 战斗 | 未完成 | 当前只有 AI Zone 配置 |
| Minecraft PvP 结算与队伍规则 | 完成 | 原生伤害边界、友伤配置、击杀归属和死亡结算 |
| 动态 Sidebar / BossBar / Action Bar | 完成 | 编辑器和行动中使用个人稳定 HUD |
| 剑/斧/弓/弩/盾牌物品分类 | 完成 | 默认领域物品与 LootTable 已使用 Minecraft 语义 |
| 物资仓库与出战栏 | 完成 | TrMenu 54 格分页界面 + Hibernate 事务 |
| 安全箱、商店、交易行、倒地救援 | 规划 | 尚未实现 |

## 运行结构

Game 和 Lobby 是互斥角色，不能把两个 Jar 放进同一个 Paper 服务端。

```text
Velocity / BungeeCord
  |
  +-- Lobby Paper
  |     +-- RaidCoreLobby.jar
  |
  +-- Game Paper #1
  |     +-- RaidCoreGame.jar
  |
  +-- Game Paper #2 (可选)
        +-- RaidCoreGame.jar

Lobby + Game -------- PostgreSQL
       长期玩家数据     档案/等级/余额/设置/装扮

Lobby <-------------> Redis <-------------> Game
       匹配分配/实例快照/扩容消息，全部带 TTL
```

一个 Game Paper 可以同时承载多局 Raid，每局使用独立世界副本。房间之间会隔离实体、Tab、普通聊天、私聊目标、加入/退出提示、死亡提示和击杀提示。Lobby 只负责大厅、菜单和匹配，不承载 Raid；Game 不提供内置 Lobby 回退。

## 安装要求

- Java 25，`java -version` 必须显示 25。
- Paper 1.21.11。
- PostgreSQL，Lobby 与 Game 连接同一个数据库和 schema。
- Redis，Lobby 与 Game 连接同一个 Redis database。
- Velocity 或 BungeeCord，负责跨服传送。
- 第一次启动可访问 Maven Central。

HikariCP、PostgreSQL JDBC、Hibernate、Jedis 和 Gson 由 Paper PluginLoader 下载到服务器 `libraries/` 缓存。它们不会打进 RaidCore Jar；坐标不变时 Paper 会复用缓存。

### 必须安装的扩展插件

以下版本来自 2026-08-09 的官方 Maven 元数据或项目正式发布，并已通过 Gradle `compileClasspath` 解析。尚未在本仓库内启动完整 Paper 集成服做交互测试。

| 插件 | 安装位置 | 用途 | 编译验证版本 | 缺失时行为 |
|---|---|---|---|---|
| LuckPerms | Game | 管理员权限 | API 5.5 | Game 拒绝加载 |
| WorldEdit | Game | 读取管理员 Cuboid 选区 | 7.4.4 | Game 拒绝加载 |
| DecentHolograms | Game | 全部编辑对象的个人标签、选中详情和验证错误 | 2.10.1 | Game 拒绝加载 |
| PlaceholderAPI | Game | 对外提供地图、对局、队伍、余额、物资价值和剩余时间 | 2.12.3 | Game 拒绝加载 |
| TrMenu 社区版 | Lobby + Game | 主菜单、仓库、设置、外观、Loot、编辑工具栏和对象属性 | 3.12.5 | 对应服务端拒绝加载 |

#### TrMenu 3.12.5（硬依赖）

RaidCore 已针对社区版 **TrMenu 3.12.5** 做了适配。官方资料和源码入口：

- 文档：[TrMenu v3 文档](https://hhhhhy.gitbook.io/trmenu-v3)
- 社区仓库：[CoderKuo/TrMenu](https://github.com/CoderKuo/TrMenu)
- 下载：[TrMenu-3.12.5.jar](https://github.com/CoderKuo/TrMenu/releases/download/trmenu/TrMenu-3.12.5.jar)

安装时把下载的 `TrMenu-3.12.5.jar` 分别放进 **Lobby 和 Game 两个服务端自己的 `plugins/` 目录**，然后重启对应服务端。发布文件 SHA-256 为 `6f8019dcb20cecda3d16e8dfb5f6aad08f2ece603350f73759976fd1926281f6`。TrMenu 是独立插件，不会被下载或打包进 RaidCore Jar。当前适配会严格检查版本必须是 `3.12.5`；缺失、被禁用或版本不匹配都会阻止对应 RaidCore 插件继续运行。

RaidCore 会在 TrMenu 已加载时，把没有同名文件的内置模板复制到 TrMenu 的 `menus/` 目录：

```text
Lobby/plugins/TrMenu/menus/raidcore_lobby_main.yml
Lobby/plugins/TrMenu/menus/raidcore_lobby_settings.yml
Lobby/plugins/TrMenu/menus/raidcore_lobby_cosmetics.yml
Lobby/plugins/TrMenu/menus/raidcore_lobby_warehouse.yml
Game/plugins/TrMenu/menus/raidcore_editor_toolbar.yml
Game/plugins/TrMenu/menus/raidcore_editor_object.yml
Game/plugins/TrMenu/menus/raidcore_loot.yml
```

已有文件绝不会被覆盖，管理员可以直接修改材质、布局和文字。RaidCore 优先调用 TrMenu 3.12.5 的公开 `TrMenuAPI`（兼容社区版历史上使用过的 `me.arasple.mc.trmenu` 与当前源码使用的 `trplugins.menu` 两套包名）；只有公开 API 不可用时，才由控制台通过同版本的 `/trmenu open` 兼容路径打开菜单，普通玩家不需要 TrMenu 管理员权限。首次复制模板后会要求 TrMenu 重载；约 10 秒内仍缺少任一必需菜单时，对应 RaidCore 插件会停用并在日志列出菜单 ID。

所有 Inventory GUI 都由 TrMenu 创建和保护，没有原生 GUI fallback。动态仓库物品、共享 Loot 和地图对象属性通过 TrMenu 的 `CustomItemSourceEvent` 取得真实 `ItemStack`。点击只会调用 RaidCore 的动作端点；动作端点会再次检查对应 TrMenu 菜单仍处于打开状态，并检查玩家数据、房间、距离、权限、编辑会话、对象和当前页，再执行 Hibernate 事务或本局状态修改。复制菜单内部动作命令、关闭菜单后重放命令，都不能绕过这些校验。这样既使用 TrMenu 的虚拟菜单能力，也不把物品所有权和结算权限交给 YAML 脚本。

菜单关闭也由 TrMenu 会话负责：玩家断线、离开 Loot、编辑操作失效、数据尚未就绪或插件停服时，RaidCore 会调用 TrMenu 3.12.5 的 `MenuSession.close(...)` 和 `shut()`，关闭数据包窗口、触发关闭事件、取消菜单任务并清理查看会话。没有 Bukkit 原生窗口 fallback；如果 TrMenu API 不兼容，RaidCore 会记录明确的集成错误，而不是悄悄切换到第二套菜单生命周期。

### 其他插件评估

下面这些插件都逐项评估过，但当前不加入硬依赖。硬依赖不是“装得越多越好”：只有 RaidCore 正在消费它的公开 API、能用它替代重复实现，并且不会抢走 PostgreSQL/地图快照的事实所有权，才进入启动链。

| 插件 | 当前决定 | 评估结论 |
|---|---|---|
| ProtocolLib | 暂不作为硬依赖 | Paper 1.21.11 已覆盖事件、Sidebar、Tab 和房间隔离；只有将来需要数据包级实体裁剪或自定义点击协议时，才增加独立 ProtocolLib Adapter。 |
| Citizens | 暂不作为硬依赖 | 适合托管 Bukkit NPC，但会与 MythicMobs 的生物定义重叠；AI Zone 目前只有地图定义，尚未消费 Citizens API。 |
| MythicMobs | 暂不作为硬依赖 | 适合复杂技能和生物脚本，但属于独立内容系统，且部分发行形态受许可影响；确定 PvE 内容方案后只能在 Citizens/MythicMobs 中选择一个适配器。 |
| ItemsAdder | 暂不作为硬依赖 | 可提供资源包和自定义物品外观，但不能成为 RaidCore 仓库和结算的事实源；强制它会让纯原版服务器无法部署。 |
| Oraxen | 暂不作为硬依赖 | 与 ItemsAdder 属于同类外观方案，不能同时硬依赖；未来若选择 Oraxen，只增加物品外观适配器，不改变 PDC + Hibernate 结算。 |
| FAWE | 暂不作为硬依赖 | FAWE 是 WorldEdit 的高性能操作实现；RaidCore 当前只读取选区、不批量改方块，强制安装反而增加与 WorldEdit 的版本耦合。 |
| Vault | 暂不作为硬依赖 | Vault 适合统一外部经济，但 RaidCore 的金币必须和 PostgreSQL 物资结算在同一事务内，不能让另一个经济插件成为权威余额。未来可做只读展示适配器。 |

## 安装

### 1. 准备 PostgreSQL

示例仅用于开发，请更换密码：

```sql
CREATE USER raidcore WITH PASSWORD 'replace-this-password';
CREATE DATABASE raidcore OWNER raidcore;
```

默认 `database.orm.schema-action: update` 由 Hibernate 管理当前实体表。生产结构稳定后建议改为 `validate`，并使用受控迁移。不要在生产使用 `create` 或 `create-drop`。

### 2. 准备两套 Paper

分别创建 Lobby 与 Game 服务端，使用 Paper 1.21.11 和 Java 25。不要共用运行目录。

### 3. 安装 Jar

```text
Lobby/plugins/RaidCoreLobby-0.1.0-SNAPSHOT.jar
Game/plugins/RaidCoreGame-0.1.0-SNAPSHOT.jar
```

必须插件放进对应服务端的 `plugins/`。Lobby 和 Game 都要各自安装同一个 TrMenu 3.12.5 社区版 Jar；Game 还必须安装 LuckPerms、WorldEdit、DecentHolograms 和 PlaceholderAPI。任一硬依赖缺失时，Paper 会拒绝加载对应 RaidCore 插件。

### 4. 第一次启动

新安装只需要维护：

```text
plugins/RaidCoreLobby/config.yml
plugins/RaidCoreLobby/messages.yml

plugins/RaidCoreGame/config.yml
plugins/RaidCoreGame/messages.yml
plugins/RaidCoreGame/imports/              # 等待导入的普通世界或完整地图包
plugins/RaidCoreGame/maps/<map-id>/map.yml
```

旧版独立 `database.yml` 与 `redis.yml` 会迁入 `config.yml`。旧 `auto-setup.yml` 不再读取，也不会在新安装中生成。

### 5. 配置网络

Lobby 与 Game 的 PostgreSQL、Redis地址和凭据必须一致。Game 还需要配置：

- `server.id`：Redis 网络内唯一，例如 `game-01`。
- `server.proxy-name`：代理中的名字，例如 `raid-01`。
- `network.lobby-server-name`：代理中的 Lobby 名字，例如 `lobby`。

Game 会把 `server.id`、`server.proxy-name` 和房间列表发布到 Redis，Lobby 直接使用快照里的代理名称传送玩家，不再维护第二份 Game 名称清单。玩家到达 Game 后还会校验分配中的玩家 UUID、内部服务器 ID 和代理后端名；任意一项不一致都会拒绝进入并送回 Lobby。

执行 `/raid info` 检查版本、模块和运行数据。

## 5 分钟快速开始

地图采用 **Import First**：管理员优先导入已有 Minecraft 世界，不创建空白地形。普通世界只需要 `level.dat`；带 `map.yml` 的完整包还会保留已有点位。导入会后台复制原图，原目录不会被修改。

1. 把普通 Minecraft 世界或完整地图包放入 Game 服务端：

```text
plugins/RaidCoreGame/imports/factory/level.dat
plugins/RaidCoreGame/imports/factory/region/
# 可选：plugins/RaidCoreGame/imports/factory/map.yml
```

2. 给 Builder 分配编辑权限，然后通过代理进入 `plugins/RaidCoreGame/config.yml` 中 `server.proxy-name` 对应的 Game backend。例如代理名为 `raid-backend-01`：

```text
/lp user <玩家名> permission set raid.editor true
/lp user <玩家名> permission set raid.editor.map true
/server raid-backend-01
```

没有匹配分配时，只有 `raid.editor` 或 `raid.admin` 玩家可以留在 Game；他们会进入彼此隔离的地图编辑组。普通玩家仍会被送回 Lobby。Redis 查询失败不会被误判为编辑入口。

3. 使用 Tab 选择地图文件夹并导入：

```text
/raid map import factory
```

导入在后台完成。看到“已导入地图”后进入编辑器：

```text
/raid editor enter factory
/raid editor menu
```

4. 使用 WorldEdit 木斧框出整张地图的可游玩范围，然后登记 Bounds：

```text
//wand
# 左键 Pos1，右键 Pos2
/raid editor map bounds
```

5. 站到合适位置创建出生点和物资点；撤离点、Loot Zone、AI Zone 和 POI 使用 WorldEdit 选区创建：

```text
/raid editor spawn create
/raid editor loot create

# 框出撤离区域后
/raid editor extraction create north_gate normal

# 可选区域
/raid editor lootzone create warehouse raid_tier_2
/raid editor ai zone create warehouse_guard
/raid editor poi create warehouse 2
```

至少放置配置要求数量的 Spawn 和 Extraction。右侧动态计分板会持续显示对象数量、完成度、评分、错误、警告、当前工具、选中对象和未保存状态。绿色是出生点，黄色是物资，红色是 AI，蓝色是撤离，紫色是 POI，白色是地图边界或当前选中对象。

6. 验证、保存、启用，再创建测试房间：

```text
/raid editor validate
/raid editor save
/raid map enable factory
/raid game create factory
```

`validate` 有 ERROR 时不能启用；WARNING 可以保存和测试，但上线前应逐项确认。正式测试时从 Lobby 输入：

```text
/play factory
```

完整流程是：**放地图 → 导入 → 进入编辑器 → 框选/放置 → Validate → Save → Enable → 创建房间**。管理员不需要打开 YAML 或手抄坐标，但必须亲自决定点位和区域；RaidCore 不会猜测地图设计师的意图。

第一个 `factory` 房间创建并发布到实例目录后，Lobby 的 `/play <map>` Tab 补全会自动出现 `factory`。`lobby.enabled-maps` 用于常驻展示和默认选择，不再是发现新房间的唯一来源。每个房间都会从 `raid_source_factory` 再复制独立的 `raid_<房间ID>` 世界，结束结算后卸载并删除该副本，导入来源与编辑源都不会被修改。

`raid_<房间ID>` 是 RaidCore 保留的临时世界命名空间；每个副本都会写入一个 RaidCore 所有权标记，Game 服务端启动时只会卸载并清理带有该标记、且上次异常停止留下的目录。
`raid_source_<map-id>` 是编辑源，明确不会被这项清理删除。仍建议不要把正式地图或其他插件的长期世界命名为 `raid_...`，以免和运行实例混淆；没有所有权标记的同名前缀世界不会被自动删除。

### 为什么是 Manual First？

RaidCore 不会自动猜测哪里应该是医院、高价值区、AI 防线或撤离出口。相同建筑在不同玩法中可能承担完全不同的作用，自动生成无法理解 Builder 的路线、视野和风险设计。

正式地图配置以管理员在编辑器中保存的内容为唯一权威。RaidCore 负责把制作过程变得直观：WorldEdit 负责选区，TrMenu 负责属性菜单，DecentHolograms 和粒子负责空间标记，动态计分板显示进度，Validator 自动发现错误，revision 负责回滚。地图原方块不会被编辑器修改。

## 地图编辑器

### 选区

安装 WorldEdit 时，RaidCore 优先读取当前 WorldEdit 选区：

```text
//wand
```

木斧左键设置 Pos1，右键设置 Pos2。完成后直接执行 RaidCore 的区域命令。

WorldEdit 是 Game 的硬依赖；RaidCore 仍保留带 PDC 标记的 `/raid editor wand` 作为第二套选区输入方式，左键方块设位置 1，右键方块设位置 2。显示名称被修改也不会让它失效。

第一阶段只保存精确的轴对齐 Cuboid。WorldEdit 多边形选区会转换为包围它的 Cuboid。

### 可视化

编辑器每位管理员各自可见：

- 白色：地图边界或当前选中对象。
- 绿色：Spawn。
- 黄色：Loot / Loot Zone。
- 红色：AI Zone。
- 蓝色：Extraction。
- 紫色：POI。

HUD 显示地图、工具、选中对象和 `* UNSAVED`。附近对象通过 Chunk bucket 查询，默认每 10 ticks 刷新，单帧最多 160 个，不会每 tick 扫描全图。

DecentHolograms 是 Game 的硬依赖。预览范围内的 Spawn、Loot、Loot Zone、AI Zone、Extraction 和 POI 都会显示只对当前管理员可见的简洁标签；选中对象会升级为详细标签，执行验证后带 ERROR 的对象会显示具体原因。标签按“选中对象 → 最近的错误对象 → 最近的普通对象”排序，并受 `maximum-holograms-per-player` 独立限制。所有悬浮文字只通过 DecentHolograms 创建，没有 TextDisplay 或 ArmorStand 后备实现；编辑器的 HUD 和粒子仍由 Paper 原生能力渲染。

### 编辑安全

- 每位管理员有独立 Draft，互不共享未保存修改。
- 同一地图被其他管理员先保存时，旧 Draft 会遇到 revision 冲突，不能静默覆盖。
- 管理员临时掉线不会立刻丢弃未保存草稿；本次服务端运行期间重连并进入同一地图会恢复草稿。存在未保存修改时仍不能切换到另一张地图。
- 最多保留 100 步 Undo/Redo；保存成功后历史会清空，以新 revision 作为新的撤销基线，旧 revision 不会被撤销操作重新带回。
- `exit` 不会丢弃未保存修改；必须先保存或明确使用 `exit discard`。
- 成功退出时会同步关闭编辑器 TrMenu、移除个人 DecentHolograms 标签与点击标记，并取消尚未确认的危险操作。
- 批量删除先进入 15 秒确认窗口，再执行 `/raid editor confirm`。
- 保存使用临时文件与原子替换。

## 命令大全

### Lobby

| 命令 | 作用 |
|---|---|
| `/play` | 使用默认地图加入匹配 |
| `/play <map>` | 指定地图加入匹配，Tab 会列出常驻地图与在线 Game 房间实际公布的地图 |
| `/leavequeue` | 取消当前匹配排队 |
| `/lobby` / `/menu` | 打开大厅主菜单 |
| `/lobby warehouse` | 打开物资仓库与下一局出战栏 |
| `/lobby settings` | 打开大厅个人设置 |
| `/lobby cosmetics` | 打开外观收藏 |
| `/lobby setting toggle <setting>` | TrMenu 设置按钮动作；重新校验数据后保存开关，参数支持 Tab |
| `/lobby cosmetic equip <index>` / `unequip` | TrMenu 外观按钮动作；保存或卸下外观 |
| `/lobby warehouse page <previous\|next>` | TrMenu 仓库翻页动作 |
| `/lobby warehouse move <0-35>` | TrMenu 仓库格子动作；事务性地在仓库与出战栏之间移动 |

### 地图

| 命令 | 作用 |
|---|---|
| `/raid map import <source> [target]` | 后台导入普通 Minecraft 世界或带 `map.yml` 的完整地图包；来源和默认目标支持 Tab 补全 |
| `/raid map create <map> <world>` | 高级兼容入口：登记已加载世界并从零编辑 |
| `/raid map list` | 列出地图 |
| `/raid map info <map>` | 查看 revision 与对象数量 |
| `/raid map preview <map>` | 进入个人可视的预览与高级编辑模式 |
| `/raid map enable <map>` | 验证通过后启用 |
| `/raid map disable <map>` | 禁止创建新对局 |

### 编辑会话

| 命令 | 作用 |
|---|---|
| `/raid editor` | 按当前世界或默认地图进入编辑模式；再次执行会退出编辑模式 |
| `/raid editor enter <map>` | 进入指定地图的编辑模式 |
| `/raid editor exit [discard]` | 退出；`discard` 明确丢弃修改 |
| `/raid editor status` | 查看当前工具、选中项与保存状态 |
| `/raid editor help` | 查看简短帮助 |
| `/raid editor menu` | 使用 TrMenu 打开完整编辑工具栏 |
| `/raid editor property open` | 使用 TrMenu 打开当前选中对象的动态属性菜单 |
| `/raid editor property <action> <forward\|reverse>` | TrMenu 属性按钮动作；再次校验对象和权限后修改草稿 |
| `/raid editor wand` | 获取内置选区工具 |
| `/raid editor map bounds` | 当前选区设为地图边界 |
| `/raid editor tool <tool>` | 切换当前编辑工具，方便配合快捷栏和预览 |

### 创建对象

| 命令 | 作用 |
|---|---|
| `/raid editor spawn create [id]` | 在当前位置创建出生点 |
| `/raid editor spawn region create <id>` | 从选区创建出生区域；运行时只使用安全落脚点，并把单次检查限制在 24 个候选内 |
| `/raid editor loot create` | 当前坐标创建虚拟 Loot，自动 ID 与默认表 |
| `/raid editor loot create <id> <loot-table>` | 指定 ID 和 LootTable |
| `/raid editor loot bind` | 把准星所指方块绑定为 Loot |
| `/raid editor spawn team <team>` | 修改出生点队伍：`players`/`any` 通用，`red`=队伍 1，`blue`=队伍 2，也可用 `team3`、`team_3` 等数字队伍 |
| `/raid editor spawn weight <weight>` | 修改出生点在当前队伍候选池内的随机权重 |
| `/raid editor spawn enable` | 启用选中的出生点 |
| `/raid editor spawn disable` | 停用选中的出生点 |
| `/raid editor loot type <container-type>` | 修改物资容器类型 |
| `/raid editor loot table <loot-table>` | 修改物资掉落表 |
| `/raid editor loot tier <1-4>` | 修改物资稀有度 |
| `/raid editor loot enable` | 启用选中的物资点 |
| `/raid editor loot disable` | 停用选中的物资点 |
| `/raid editor lootzone create <id> [loot-table]` | 从选区创建 Loot Zone；省略掉落表时使用当前注册的默认表 |
| `/raid editor lootzone table <loot-table>` | 修改物资区域的掉落表 |
| `/raid editor lootzone tier <1-4>` | 修改物资区域层级 |
| `/raid editor lootzone density <value>` | 修改物资区域密度 |
| `/raid editor lootzone danger <0-1>` | 修改物资区域危险度 |
| `/raid editor lootzone enable` | 启用选中的物资区域 |
| `/raid editor lootzone disable` | 停用选中的物资区域 |
| `/raid editor ai zone create <id>` | 从选区创建 AI Zone |
| `/raid editor ai type <type>` | 修改生物区域类型标签 |
| `/raid editor ai minimum <count>` | 修改生物区域最小数量 |
| `/raid editor ai maximum <count>` | 修改生物区域最大数量 |
| `/raid editor ai respawn <seconds>` | 修改生物区域重生间隔 |
| `/raid editor ai difficulty <value>` | 修改生物区域难度 |
| `/raid editor ai team <team>` | 修改生物区域阵营 |
| `/raid editor ai enable` | 启用选中的生物区域 |
| `/raid editor ai disable` | 停用选中的生物区域 |
| `/raid editor extraction create <id> [normal]` | 创建普通撤离点 |
| `/raid editor extraction create <id> item <item-id>` | 创建钥匙撤离点 |
| `/raid editor extraction create <id> paid <cost>` | 创建付费撤离点 |
| `/raid editor extraction create <id> timed <seconds>` | 创建计时撤离点 |
| `/raid editor extraction name <name>` | 修改撤离点显示名 |
| `/raid editor extraction duration <seconds>` | 修改撤离点倒计时 |
| `/raid editor extraction type <type> [value]` | 修改撤离点类型和条件 |
| `/raid editor extraction condition <value>` | 修改撤离点条件值 |
| `/raid editor extraction enable` | 启用选中的撤离点 |
| `/raid editor extraction disable` | 停用选中的撤离点 |
| `/raid editor poi create <id> [tier]` | 从选区创建 POI，Tier 为 1 至 4 |
| `/raid editor poi name <name>` | 修改兴趣点名称 |
| `/raid editor poi tier <1-4>` | 修改兴趣点层级 |
| `/raid editor poi enable` | 启用选中的兴趣点 |
| `/raid editor poi disable` | 停用选中的兴趣点 |

### 调整与检查

| 命令 | 作用 |
|---|---|
| `/raid editor select <id>` | 选择地图对象，Tab 会列出当前地图所有对象 |
| `/raid editor info [id]` | 查看准星指向或指定对象的完整字段，并自动选择对象 |
| `/raid editor tp [id]` | 安全传送到准星指向或指定对象附近 |
| `/raid editor move` | 把选中对象移到当前位置 |
| `/raid editor clone <new-id>` | 在当前位置克隆选中对象 |
| `/raid editor copy` | 把当前选中对象放入本次编辑会话的剪贴板 |
| `/raid editor paste <new-id>` | 在当前位置粘贴剪贴板对象，并使用新的 ID |
| `/raid editor delete [id]` | 删除指定或选中对象 |
| `/raid editor delete type <type>` | 请求批量删除同类对象 |
| `/raid editor batch enable [type]` | 按当前选区批量启用对象 |
| `/raid editor batch disable [type]` | 按当前选区批量停用对象 |
| `/raid editor batch table <loot-table>` | 按当前选区统一设置 Loot 组合 |
| `/raid editor batch tier <1-4>` | 按当前选区统一设置 Loot/POI 等级 |
| `/raid editor batch delete [type]` | 按当前选区请求批量删除，需二次确认 |
| `/raid editor confirm` | 确认待执行的危险操作 |
| `/raid editor undo` / `redo` | 撤销或重做 |
| `/raid editor show\|hide <type\|all>` | 过滤个人可视对象 |
| `/raid editor show\|hide bounds` | 单独显示或隐藏地图边界 |
| `/raid editor radius <16..512>` | 调整可视半径 |
| `/raid editor nearby` | 按距离列出附近对象，聊天结果可点击选择 |
| `/raid editor validate` | 显示评分、ERROR 和 WARNING |
| `/raid editor save` | 保存新 revision |
| `/raid editor history` | 查看当前与可回滚 revision |
| `/raid editor rollback <revision>` | 恢复旧版本并保存为新 revision |

### 对局

| 命令 | 作用 |
|---|---|
| `/raid game create [map]` | 创建 WAITING 对局 |
| `/raid game list` | 查看每个房间的地图、状态、人数与世界副本准备状态 |
| `/raid game start <id>` | 管理员跳过自动开局倒计时；正常房间达到最低人数会自动开始 |
| `/raid game stop <id>` | 停止并清理对局 |

编辑器在预览半径内会创建仅当前管理员可见的隐形交互标记：左键选择对象，潜行右键打开属性菜单。菜单中的物品不能移动、拖拽、Shift 点击或数字键换位；删除仍需 `/raid editor confirm`。`validate` 后带对象 ID 的 ERROR 会以红色叉号和悬浮说明显示，地图改动后旧报告会自动失效。

## 验证规则

当前验证至少检查：

- 世界是否加载，地图是否有 Bounds。
- 最低 Spawn 与 Extraction 数量。
- 至少一个普通撤离点。
- 对象是否全部在 Bounds 内。
- 对象 ID 是否重复。
- LootTable 与条件撤离物品是否存在。
- `PAID` / `TIMED` 条件是否为非负整数。
- Spawn 之间、Spawn 到 Extraction 的距离。
- Loot 重叠与 Extraction 区域重叠。

ERROR 会阻止 `/raid map enable`。启动时已启用地图若验证失败，会保存一个禁用 revision，防止实例继续创建。

## 权限

`raid.admin` 是总管理权限。下面权限适合用 LuckPerms 分配给不同地图岗位：

| 权限 | 作用 |
|---|---|
| `raid.editor` | 允许无匹配分配进入 Game 的独立编辑组，并使用编辑器通用查看功能 |
| `raid.editor.map` | 创建地图、修改 Bounds、启用/禁用 |
| `raid.editor.spawn` | 编辑 Spawn |
| `raid.editor.loot` | 编辑 Loot 与 Loot Zone |
| `raid.editor.ai` | 编辑 AI Zone |
| `raid.editor.extraction` | 编辑 Extraction |
| `raid.editor.poi` | 编辑 POI |
| `raid.editor.delete` | 删除与批量删除 |
| `raid.editor.save` | 保存新 revision |
| `raid.editor.rollback` | 回滚 revision |
| `raid.command.info` | `/raid help`、`info`、`tutorial` |
| `raid.lobby.play` | Lobby 选择地图与匹配 |

LuckPerms 示例：

```text
/lp group admin permission set raid.admin true
/lp group builder permission set raid.editor true
/lp group builder permission set raid.editor.map true
/lp group builder permission set raid.editor.spawn true
/lp group builder permission set raid.editor.loot true
```

LuckPerms 是 Game 的硬依赖；权限节点由 LuckPerms 分配和审计，Bukkit OP 只作为 LuckPerms 的超级管理员语义保留。
`raid.command.info` 和 `raid.lobby.play` 默认允许普通玩家，管理与编辑权限默认仅 OP。LuckPerms
对某个节点给出明确的 `true` 或 `false` 时以该结果为准；没有配置该节点时回退到上述默认值，
所以新服不需要先配权限就能使用帮助、教程和匹配。

## PlaceholderAPI

当前真实提供：

```text
%raidcore_map%
%raidcore_game_id%
%raidcore_game_state%
%raidcore_player_state%
%raidcore_team%
%raidcore_money%
%raidcore_level%
%raidcore_experience%
%raidcore_experience_to_next_level%
%raidcore_alive_players%
%raidcore_loot_value%
%raidcore_remaining_time%
%raidcore_extracted%
```

其中 `loot_value` 是玩家当前本局背包里 RaidCore 物资的估算总价值，
`remaining_time` 是行动还剩的秒数，`extracted` 是该玩家在当前对局是否已经成功撤离。
`level` 是行动等级，`experience` 是累计历练，`experience_to_next_level` 是距离下一级还需要的历练。
玩家不在行动中、行动尚未开始或 PlaceholderAPI 只能拿到离线玩家时，相关值会留空，
不会用 `0` 或 `false` 冒充真实状态。

## 配置文件

### `config.yml`

Game 和 Lobby 的数据库、Redis、网络与玩法配置都在各自的主配置中。数据库配置不再拆成多个文件。

Game 的 `server.minimum-players` 控制房间达到多少人后自动开始倒计时，`server.start-countdown-seconds` 控制倒计时长度。倒计时期间人数低于最低值会取消；人数重新满足后会重新开始。管理员仍可使用 `/raid game start <game>` 手工开局。

Game 的 `map-editor` 节控制正式地图制作流程：

- `retained-revisions`：旧 revision 数量。
- `confirmation-seconds`：危险操作确认时间。
- `default-visibility-radius`：进入编辑器的默认半径。
- `visualization.refresh-ticks`：渲染间隔。
- `visualization.maximum-objects-per-frame`：单帧上限。
- `visualization.maximum-holograms-per-player`：每位管理员同时显示的个人全息标签上限；选中和错误对象优先。
- `validation.minimum-spawns` / `minimum-extractions`：启用地图必须具备的出生点和撤离点数量；不足会产生 ERROR，不能启用。
- `validation.minimum-loot-zones` / `minimum-ai-zones` / `minimum-pois`：建议具备的物资区、AI 区和兴趣点数量；不足只产生 WARNING，适合纯 PvP 或低 PvE 地图，不会强行阻止启用。
- `validation.minimum-spawn-distance` / `minimum-spawn-extraction-distance`：出生点之间、出生点与撤离点之间的最小水平距离。

### `messages.yml`

保存玩家消息与菜单文字。支持 Adventure MiniMessage。修改后需要重启；当前没有危险的 `/reload` 支持。

### `maps/<map-id>/map.yml`

保存管理员编辑结果，包括：

```text
schema-version
map-id / display-name / world
revision / enabled / metadata
bounds
spawns
loot-points / loot-zones
ai-zones
extractions
pois
```

旧版本位于 `maps/<map-id>/revisions/`。YAML 可用于审计和备份，但正常编辑请使用游戏内命令。

## PostgreSQL、Hibernate 与 HikariCP

RaidCore 明确使用 **Hibernate ORM + HikariCP + PostgreSQL**，不使用 Spring Boot，也不使用 JPA 容器。

Hibernate 负责实体映射、事务 Session 和批处理；HikariCP 负责连接池。当前持久化包含档案、等级、余额、玩家设置、装扮、物资仓库、出战栏、行动物品与幂等结算。金币变动写入 `raid_economy_transactions`，历练奖励写入 `raid_progression_transactions`；两者都以 transaction ID 防止网络重试造成重复发放或重复扣除。Redis 只保存匹配、实例目录与临时分配，不替代 PostgreSQL。完整表结构与命名规则见 [DATABASE.md](DATABASE.md)。

`database.table-prefix` 可在自动建表时为所有表名与索引名统一添加前缀（默认空即不加），便于多套部署共用同一个库或 schema。前缀须以小写字母开头，仅含小写字母、数字、下划线，最长 20 字符；Lobby 与 Game 必须配置一致，已投产后修改前缀等于换一套全新表。

常用 HikariCP 配置均有注释：最大/最小连接、获取与校验超时、空闲回收、生命周期、Keepalive、泄漏检测、启动失败策略、事务隔离、初始化 SQL、内部查询隔离和 JMX。

常用 Hibernate 配置：

- `schema-action`：开发可用 `update`，生产建议 `validate`。
- `statement-batch-size`：JDBC 写入批次。
- `default-batch-fetch-size`：关联批量读取。
- `show-sql` / `format-sql`：临时排障。
- `generate-statistics`：性能诊断，平时关闭。

## 常见问题

### 一定要编辑 YAML 吗？

不需要。游戏内编辑器是主要入口，YAML 用于存储、审计和备份。

### 一定要安装这些扩展插件吗？

需要。Game 的硬依赖是 LuckPerms、WorldEdit、DecentHolograms、PlaceholderAPI 和 TrMenu 3.12.5；Lobby 需要 TrMenu 3.12.5。缺少其中任何一个，Paper 会拒绝加载对应 RaidCore 插件。`/raid editor wand` 只是 WorldEdit 已安装后的第二套选区操作，不是替代依赖。

### 两个管理员可以同时编辑吗？

可以，各自有独立 Draft。但同一地图只有先保存的人成功；后保存的旧 revision 会被拒绝，必须重新进入后再合并修改。

### 为什么地图不能启用？

执行 `/raid editor validate`。ERROR 必须修复，WARNING 不阻止启用。

### 可以同时开多局吗？

可以，上限由 `game.max-instances` 控制。每局捕获独立地图快照与世界副本。

### 可以不开 PostgreSQL 测试吗？

不可以。Game 和 Lobby 都把 PostgreSQL 视为强依赖，连接失败会拒绝启动，避免运行中才丢数据。

### Redis 断了会怎样？

插件保持启动并等待恢复，但匹配、跨服分配和实例目录不可用。长期玩家数据仍在 PostgreSQL。

## 故障排查

### 插件无法启动

确认 Java 25、Paper 1.21.11，并确认 Game 与 Lobby Jar 没有放在同一个服务端。查看日志中的具体启动阶段。

### 数据库连接失败

检查 `database.host`、端口、库名、账号、密码、schema 与 `ssl-mode`。远程 PostgreSQL 同时需要防火墙和 `pg_hba.conf` 放行。

### WorldEdit 选区无法读取

确认两个位置都在当前地图世界，选区完整，并检查启动日志是否显示 `Integration WorldEdit: FOUND`。仍失败时使用 `/raid editor wand` 重新设置两个点；如果 Game 启动日志显示依赖错误，先修复 WorldEdit 安装或版本，不要绕过硬依赖。

### 看不到编辑器标记

确认已 `/raid editor enter <map>`、人在正确世界、对象类型没有被 hide，且 `radius` 足够大。再检查 `Integration DecentHolograms: FOUND`；依赖未正确加载时 Game 不会进入可编辑状态。

### revision 冲突

另一名管理员已经保存。不要覆盖磁盘文件；先 `/raid editor exit discard`，重新进入最新 revision，再重做需要保留的修改。

### 对局世界创建失败

确认地图源世界目录或 `game.map-template-dir/<map-id>/` 存在。生产环境保持 `allow-flat-fallback: false`。

### 撤离后一直显示“物资正在安全送回”

不要手工切换到 Lobby，也不要重启 Game。系统会使用倒计时结束时锁定的物资快照自动重试，不会重复入库。管理员应检查 PostgreSQL、Hikari 连接池和 Game 日志；恢复连接后，成功回调会自动完成撤离。若日志报告“既有死亡结算冲突”，数据库中的既有终态优先，系统不会再重复写入物资。

### 进入行动后一直显示“正在领取出战装备”

玩家会暂时保持无敌、背包为空且不能移动或拾取，避免在装备状态不明确时进入战斗。不要手工跨服或反复重连，系统会自动重试。若某件装备无法转换成 Minecraft 物品，原有出战装备会先安全退回物资仓库，完成后才取消本局并返回 Lobby。管理员应检查 Game 日志、数据库连接和物品定义；装备尚未确认退回时，插件不会提前放行玩家。

## Build

```bash
./gradlew clean build
./gradlew test
```

构建产物：

```text
game/build/libs/RaidCoreGame-<version>.jar
lobby/build/libs/RaidCoreLobby-<version>.jar
```

第三方运行库由 Paper 下载，五个硬依赖插件由服务器管理员单独安装；它们都不应出现在 RaidCore Jar 内。

## Developer Guide

```text
Paper
  |
  +-- game        Game 插件、编辑器、实例世界、集成 Adapter
  +-- lobby       Lobby 插件、菜单与匹配
  +-- core        Hibernate、Redis、领域实现与统一地图 Domain
  +-- api         公共服务、事件与值对象
```

地图代码分层：

```text
core/map
  ManualRaidMap / MapObject
  ManualMapEditor / EditorSession
  MapObjectIndex / ManualMapValidator
  RaidMapSnapshot

game/plugin/editor
  ManualMapStore / ManualMapRegistry / ManualMapService
  EditorSelection / Visualization / Wand / Confirmation
  ManualRaidMapProvider

game/plugin/integration
  WorldEdit / LuckPerms / DecentHolograms / PlaceholderAPI required adapters

core/bukkit
  TrMenuBridge（公开 API、动态物品源、模板加载门禁，不打包依赖）

game/plugin/editor + game/plugin/loot
  TrMenu 动作控制器（会话/权限/房间/距离校验）

lobby/menu
  TrMenu 大厅控制器（Hibernate 仓库、设置和外观数据源）
```

Domain 不 import Bukkit 或第三方插件。Paper 世界访问、PDC、粒子和第三方 API 只在 Game Adapter 层。运行中对局消费的是创建时捕获的 API 快照，不读取当前可变地图注册表。

新增地图对象时必须同时处理：领域校验、不可变编辑操作、YAML 往返、Chunk 索引、可视化、命令、revision 测试和运行时编译。不要把逻辑塞进一个 God Class。

## 已知限制与下一步

- 当前编辑器同时支持命令、TrMenu 虚拟工具栏、附近点击选择和动态属性菜单；选区批量启用/停用、物资组合、等级与删除均有命令入口。
- 只正式支持 Cuboid；WorldEdit Polygon 会变成包围盒。
- DecentHolograms 会标出预览半径内的地图对象，但默认每位管理员最多同时显示 32 个；选中和错误对象优先，避免 200+ Loot 地图产生无上限实体压力。
- 地图必须由管理员人工放置点位和划分区域；当前不提供自动扫描或自动生成正式地图配置。
- AI Zone 尚未生成 AI 实体。
- Loot Point 已提供右键搜索与共享容器 UI。
- 仓库、出战栏、装备/物资损失和撤离结算已经闭环。
- 未在本仓库内运行五个硬依赖插件的完整 Paper 交互服，只验证了公开 API 编译与依赖解析；正式部署仍应检查每个插件的实际版本和启动日志。

下一阶段最值得优先完成：**AI Zone 的 Minecraft 生物生成、寻路和 PvE 行为**。随后可以实现安全箱、商店、交易行和倒地救援等跨局系统。
