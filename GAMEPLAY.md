# EscapeFromCraftov 完整玩法与运营手册

> 本文是 EscapeFromCraftov 的统一手册，面向三类读者：第一次玩「搜打撤」的玩家、负责开服和做地图的管理员、需要维护或扩展插件的开发者。
>
> 看到“已实现”才代表当前代码已经接通；“规划”或“限制”不会在游戏里假装存在。

## 1. EscapeFromCraftov 是什么

EscapeFromCraftov 是 Paper 1.21.11 上的 Minecraft 搜打撤玩法框架，游戏名为 **Escape from Craftov / 逃离块科夫**。

“搜打撤”可以用一句话理解：

> 带着装备进入地图，搜索物资，使用 Minecraft 的剑、斧、弓、弩、盾牌、护甲、药水和地形应对危险，然后活着到撤离点；成功离开后，物资才算真正带回家。

这里没有枪械系统。战斗就是 Minecraft 原生战斗：近战、投射物、格挡、附魔、药水、食物、环境伤害和生物伤害。

### 1.1 先认识几个词

| 词 | 在 EscapeFromCraftov 里的意思 |
| --- | --- |
| 行动 / Craftov | 从进入地图到成功撤离、死亡或时间结束的一整局。 |
| Loot | 英文里“战利品/掉落物”的意思。EscapeFromCraftov 中它就是可以在地图中找到的物品。 |
| 搜索 Loot | 走到一个物资点，右键打开搜索箱，查看并拿走里面的物品；不是输入指令，也不是扫描玩家。 |
| Loot Point | 地图作者放置的一个物资点，可以绑定箱子，也可以是看不见的虚拟物资点。 |
| 公共搜索箱 | 同一局玩家看到的是同一个箱子；第一次打开时生成内容，之后只剩没被拿走的物品。 |
| 物资仓库 | 你在大厅里整理的安全储物空间。成功撤离的战利品会放到这里；死亡不会把本局携带物品放回这里。它不是地图里的箱子，也不是本局背包。 |
| 出战栏 / Loadout | 在大厅选定、下一局准备带入地图的物品。当前最多 9 个物品栈。 |
| 本局背包 | Minecraft 原生玩家背包。进入 Game 后它就是行动背包；只有成功撤离，里面的战利品才会送进物资仓库。 |
| 撤离 | 在指定区域站满倒计时离开地图。离开区域会取消进度。 |
| POI | Point of Interest，兴趣点。它是地图上一个值得探索的区域，例如仓库、建筑群或地下设施。 |
| AI 区域 | 预留给生物或其他 PvE 威胁的地图区域。当前只保存地图数据，AI 战斗仍在开发。 |

## 2. 玩家指南

### 2.1 一局行动的完整流程

1. 在 Lobby 打开“游戏菜单”，进入“物资仓库与出战”。
2. 把想带走的物品点击放入上方的出战栏。物品移动成功后，下一局就会按这份出战清单准备。
3. 点击“开始游戏”，选择地图并进入匹配队列。
4. 传送到 Game 后，服务器原子领取出战栏。装备会进入你的 Minecraft 背包，右上角/右侧 HUD 会显示地图、队伍、时间和携带价值。
5. 在建筑、道路、房间和地下区域寻找 Loot Point。对准物资点右键就是“搜索 Loot”。
6. 取走物品后决定继续探索，还是带着已有收获前往撤离点。
7. 进入撤离区域后开始倒计时。保持在区域内，BossBar 会显示进度；离开区域会取消。
8. 撤离完成后，服务器读取本局背包，把战利品送进物资仓库，然后才把你送回 Lobby。
9. 如果死亡、主动退出或行动超时，本局 CRAFTOV 物品会丢失，不会自动回仓库。死亡掉落的物品仍是 Minecraft 世界中的物品，其他玩家可以拾取并带走。

### 2.2 物资仓库与出战栏

大厅菜单的“物资仓库与出战”是一个 54 格界面：

- 上方 9 格：下一局会带入的装备和物资。
- 中间区域：已经安全带回物资仓库的长期物品。
- 纸张/书本位置：页码和当前页面。
- 左右箭头：翻页。
- 返回按钮：回到主菜单。

菜单中的物品不能拖动、不能 Shift 点击、不能用数字键换位，也不能放入玩家背包。点击一个物品后，EscapeFromCraftov 会先确认移动成功，再刷新界面；网络异常时不会假装移动成功。

### 2.3 Minecraft 战斗

EscapeFromCraftov 不改造成枪械游戏。可用的战斗方式是：

- **剑**：稳定的近战伤害，适合走位和持续攻击。
- **斧**：单次爆发和破盾压力更高，但攻击节奏不同。
- **弓与箭**：远距离消耗、拉扯和侦察；箭矢本身也占用背包空间。
- **弩**：提前装填后进行远程爆发，适合守角和伏击。
- **盾牌**：格挡正面攻击和投射物，但会影响移动与攻击节奏。
- **护甲、附魔、药水、食物、金苹果**：改变生存和战斗选择。
- **环境**：摔落、火焰、岩浆、水、窒息和生物伤害都可能让你无法撤离。

同一 Craftov 实例外的伤害会被取消。对局尚未进入“行动中”时，玩家也不能互相伤害。默认关闭队伍友伤；管理员打开 `combat.friendly-fire` 后，队友才可以互相造成伤害。

### 2.4 队伍

匹配进入同一实例的玩家会按 `game.team-size` 自动编队。Sidebar 会显示你的队伍编号；队友仍然是 Minecraft 玩家，名字、装备和声音不会被插件伪装成机器人。

默认规则：

- 同队不能互相伤害。
- 不同队可以 PvP。
- 队伍不能跨 Craftov 互相影响。
- 队友退出或死亡后，不会因为“队伍还在”而把物品退回物资仓库。

### 2.5 撤离点

地图作者可以配置多种撤离方式：

- **普通撤离**：满足区域条件后站满引导时间即可。
- **计时开放**：行动开始经过指定秒数后才开放。
- **付费撤离**：支付金币后进入引导；同一玩家同一局不会重复扣费。
- **钥匙撤离**：背包里必须有指定物品，例如一把特殊钥匙。

撤离点是一个区域而不是单个方块。进入区域开始引导，移动离开区域会取消；重新进入可以再次开始。

### 2.6 死亡、退出和超时

死亡会进入 Minecraft 原生死亡流程。服务器在死亡结算成功后才允许返回大厅；本局装备和背包物资按“死亡损失”处理。退出服务器也按离开行动处理，避免通过 Alt+F4 逃避损失。

行动时间结束时，仍在地图中的玩家统一按未撤离处理。服务器会先为所有剩余玩家完成损失结算，再卸载实例世界。

### 2.7 你在屏幕上会看到什么

- **Sidebar**：地图、阶段、剩余时间、存活人数、队伍、背包槽位、携带价值、击杀和撤离提示。
- **BossBar**：正在撤离时显示引导进度。
- **Action Bar**：当前工具、撤离状态等短信息。
- **Title / Subtitle**：进入编辑器、进入行动、模式切换等阶段提示。
- **聊天框**：战斗击杀提示、结算结果和错误说明。
- **搜索箱 GUI**：公共 Loot 容器，只允许拿取，不能把自己的物品倒进去。

## 3. 管理员指南

### 3.1 服务端角色和代理名称

Game 和 Lobby 是两个不同的 Paper 服务端，不能把两个插件 Jar 放进同一服务端。

```text
Velocity / BungeeCord
  ├─ lobby       -> EscapeFromCraftovLobby.jar
  ├─ craftov-01     -> EscapeFromCraftovGame.jar
  └─ craftov-02     -> EscapeFromCraftovGame.jar（可选）
```

Lobby 的名字由代理配置和 `lobby` 服务端实际名称决定。每台 Game 在自己的 `config.yml` 中配置：

```yaml
server:
  id: game-01          # Redis 实例目录中的唯一技术 ID
  proxy-name: craftov-01  # Velocity/Bungee 后端名称，必须完全一致

network:
  lobby-server-name: lobby
```

Game 启动后会把 `server.id + proxy-name + 对局实例列表` 发布到 Redis。Lobby 选择实例时直接读取这个动态快照，再把同一个 `proxy-name` 放进玩家的匹配分配，最后通过 BungeeCord `Connect` 传送。Game 收到玩家时会原子消费分配，并同时校验玩家 UUID、`server.id` 和 `proxy-name`；错误后端名或过期分配不会被本服接受。不要在 Lobby 再维护一份 Game 名称列表。

### 3.2 一台 Game 同时运行多个房间

一台 Game 服务端可以同时承载多张地图、多个房间。每次创建房间时，EscapeFromCraftov 都会把对应地图模板复制成独立世界，世界名类似 `craftov_<房间ID>`；房间结束后先处理剩余玩家，再卸载并删除这份副本。正在运行的房间使用创建时捕获的地图版本，管理员保存新版本不会改变已经开始的房间。

每个玩家都有一个隔离组：Lobby 是 `lobby`，房间是 `game:<房间ID>`。这套隔离同时用于：

- **聊天**：只发送给同一房间的玩家，Lobby 和其他房间收不到。
- **实体与 Tab 可见性**：不同房间的玩家互相隐藏；同一房间正常看到彼此。
- **Tab 玩家名**：同一房间内显示 `[地图名] 队 N · 玩家名`，回到 Lobby 后恢复为普通玩家名。
- **伤害规则**：跨房间伤害和非行动阶段伤害直接取消，队友伤害按配置决定。

因此“同一 Game 服务端有很多房间”不会把玩家、聊天、背包或 PvP 事件混到一起；每个房间都拥有自己的世界、Loot 状态和生命周期。

### 3.3 安装条件

- Java 25。
- Paper 1.21.11。
- PostgreSQL：玩家档案、等级、金币、设置、装扮、仓库和出战栏的长期事实来源。
- Redis：只保存带 TTL 的匹配队列、实例目录、临时分配和扩容提示。
- Velocity 或 BungeeCord：负责跨服。
- Game 硬依赖插件：LuckPerms、WorldEdit、DecentHolograms、PlaceholderAPI、TrMenu 3.12.5。
- Lobby 硬依赖插件：TrMenu 3.12.5。
- HikariCP、Hibernate ORM、PostgreSQL JDBC、Jedis 等由 Paper PluginLoader 下载到 `libraries/`，不打包进 Jar。

数据库配置位于两个服务端各自的 `config.yml` 的 `database` 节。两边必须指向同一个 PostgreSQL 数据库和 schema。Hibernate 负责 ORM 映射；首次部署可使用 `schema-action: update`，结构稳定后改为 `validate` 并使用受控迁移。不要在生产使用 `create` 或 `create-drop`。

### 3.3.1 TrMenu 3.12.5 菜单适配

EscapeFromCraftov 支持管理员单独安装的 TrMenu 社区版 3.12.5：

- 文档：[TrMenu v3 文档](https://hhhhhy.gitbook.io/trmenu-v3)
- 源码：[CoderKuo/TrMenu](https://github.com/CoderKuo/TrMenu)

把 `TrMenu-3.12.5.jar` 分别放到 Lobby 和 Game 的 `plugins/` 目录。它不是 EscapeFromCraftov 的内置依赖，也不会被复制进 `EscapeFromCraftovGame.jar` 或 `EscapeFromCraftovLobby.jar`；两个服务端各自由 Paper 加载。TrMenu 在两个 `paper-plugin.yml` 中都是硬依赖，缺失或被禁用时对应 EscapeFromCraftov 插件不会加载。EscapeFromCraftov 启动时会在 TrMenu 的 `menus/` 目录缺少同名文件时安装默认模板：

```text
Lobby/plugins/TrMenu/menus/escapefromcraftov_lobby_main.yml
Lobby/plugins/TrMenu/menus/escapefromcraftov_lobby_settings.yml
Lobby/plugins/TrMenu/menus/escapefromcraftov_lobby_cosmetics.yml
Lobby/plugins/TrMenu/menus/escapefromcraftov_lobby_warehouse.yml
Game/plugins/TrMenu/menus/escapefromcraftov_editor_toolbar.yml
Game/plugins/TrMenu/menus/escapefromcraftov_editor_object.yml
Game/plugins/TrMenu/menus/escapefromcraftov_loot.yml
```

已经存在的文件不会被覆盖。大厅主菜单、仓库、设置、外观、行动 Loot、编辑工具栏和对象属性全部由 TrMenu 3.12.5 渲染。EscapeFromCraftov 通过 TrMenu 的动态物品源提供实时 `ItemStack`，并通过受保护命令处理点击；每次点击都会重新检查对应 TrMenu 菜单仍然打开，并检查数据状态、权限、房间、距离和当前会话。关闭菜单后重放内部动作命令不会生效。桥接同时识别社区版公开 API 的两套包名（`me.arasple.mc.trmenu` 和 `trplugins.menu`）；公开 API 不可用时才由控制台兼容路径打开菜单，不要求普通玩家拥有 TrMenu 管理权限。Hibernate 和本局容器仍是物品事实源，TrMenu 只负责菜单表现与交互。

菜单生命周期同样交给 TrMenu：断线、对局结束、编辑动作失效、数据未就绪和服务端关闭时，EscapeFromCraftov 会调用 TrMenu 3.12.5 的 `MenuSession.close(...)` 与 `shut()`，完整关闭窗口、触发关闭事件、停止菜单任务并清理会话。这里没有 Bukkit 原生窗口 fallback；API 不兼容会作为明确的集成错误暴露，不会悄悄切换到第二套菜单生命周期。

没有安装准确的 TrMenu 3.12.5 时 Paper/EscapeFromCraftov 会阻止加载。首次安装模板后 EscapeFromCraftov 会触发菜单重载；约 10 秒内仍有必需菜单未加载，会停用对应插件。这里没有原生菜单 fallback，菜单文件错误必须修复，不能带着半套界面继续运行。

### 3.4 五分钟导入并开始制作一张地图

当前正式流程是 **Import First + Manual First**：优先导入已有 Minecraft 世界，再由管理员在游戏内决定出生、物资、AI、POI 和撤离区域。普通世界只需要 `level.dat`；已经制作过 EscapeFromCraftov 点位的完整包还可以带上 `map.yml`。导入在后台复制，不覆盖原地图文件。

先用 LuckPerms 授予 `craftov.editor` 和所需的细分权限，再通过代理进入 Game 配置中的 `server.proxy-name` backend。没有匹配分配时，只有 `craftov.editor` 或 `craftov.admin` 玩家会进入独立的地图编辑组；普通玩家仍返回 Lobby。Redis 查询失败不会绕过匹配验证。

```text
/lp user <玩家名> permission set craftov.editor true
/lp user <玩家名> permission set craftov.editor.map true
/server craftov-backend-01
```

```
# 把普通 Minecraft 世界放入 plugins/EscapeFromCraftovGame/imports/factory/
# 至少包含 level.dat 和 region/；map.yml 不是必需的
/craftov map import factory
# 等聊天提示“已导入地图”后再继续
/craftov editor enter factory
/craftov editor menu
```

使用 WorldEdit 木斧框出整张可游玩区域，左键设置 Pos1，右键设置 Pos2：

```text
//wand
/craftov editor map bounds
```

然后站到目标位置放置点对象，或先用 WorldEdit 框选再创建区域对象：

```text
/craftov editor spawn create
/craftov editor loot create
/craftov editor extraction create north_gate normal

# 选做，但完整 PvPvE 地图建议配置
/craftov editor lootzone create warehouse craftov_tier_2
/craftov editor ai zone create warehouse_guard
/craftov editor poi create warehouse 2
```

右侧 Sidebar 会持续显示当前工具、选中对象、点位数量、制作完成度、地图评分、ERROR/WARNING 和 `* UNSAVED`。完成后：

```text
/craftov editor validate
/craftov editor save
/craftov map enable factory
/craftov game create factory
```

`validate` 输出 ERROR 时不能启用地图；WARNING 可以保存和测试，但上线前应逐项检查。管理员不需要打开 YAML 或记录坐标，正式点位以编辑器保存的 revision 为唯一权威。EscapeFromCraftov 不会自动猜测地图设计师的路线和风险意图，也不会修改建筑、地形或原地图文件。

地图包来源可以通过 Tab 自动列出：

```text
/craftov map import <按 Tab 选择地图文件夹>
```

插件会从 `plugins/EscapeFromCraftovGame/imports/`、配置的 `game.map-template-dir/` 和服务器世界目录读取候选文件夹。导入目标默认使用文件夹名，也可以写成 `/craftov map import <来源> <新的地图ID>`。目录必须至少包含普通世界的 `level.dat`，或包含可复用配置的 `map.yml`；两者都没有时才会拒绝。

导入完成并验证启用后，执行 `/craftov game create <地图ID>` 创建第一个房间。Game 会把这个房间写进跨服实例目录；Lobby 的 `/play <map>` Tab 候选会自动合并在线房间实际使用的地图，因此新地图不必为了第一次测试先改 Lobby 配置。`lobby.enabled-maps` 仍用于固定展示常驻地图和决定不带参数的默认选择；如果某张动态地图还没有任何房间，先在 Game 创建一个房间，它才会出现在 Lobby 的动态候选中。

Game 服务端启动时还会回收上次异常停止遗留的 `craftov_<房间ID>` 临时副本。只有带有 EscapeFromCraftov 所有权标记的副本才会被回收；`craftov_source_<地图ID>` 编辑源不属于临时副本，不会被回收，同名但没有标记的普通世界也会保留。

普通 Minecraft 世界的最小结构如下：

```text
factory/
├─ level.dat
└─ region/
```

已经配置过点位的完整 EscapeFromCraftov 地图包可以再加入：

```text
factory/
├─ map.yml          # 边界、出生点、Loot、撤离点、POI 等玩法配置
├─ level.dat        # Minecraft 世界模板
└─ region/          # 地图区块文件
```

有世界文件时，导入会生成名为 `craftov_source_<地图ID>` 的专用编辑源世界，并移除副本里的 `uid.dat` 与 `session.lock`，避免与原世界冲突。`/craftov editor enter` 会按需加载这个副本并传送管理员。每个正式房间再从编辑源复制自己的 `craftov_<房间ID>` 世界；房间结束后销毁实例副本，Builder 原图和导入来源始终不被修改。只有 `map.yml`、没有世界文件的配置包会继续引用其中写明的现有源世界。

### 3.5 编辑器工具栏和预览

`/craftov editor menu` 使用 TrMenu 打开受保护的虚拟物品栏工具栏：

- 地图边界、出生点、Loot、Loot 区域、AI 区域、撤离区域、POI：切换当前工具。
- 撤销、重做：操作当前编辑草稿。
- 保存地图：原子保存一个新 revision。
- 关闭：退出工具栏，不会自动丢弃草稿。

编辑器右侧 Sidebar 会动态显示地图 revision、是否有未保存修改、当前工具、选中对象、设置进度、质量评分、ERROR/WARNING、各类对象数量和边界状态。粒子颜色约定：橙色 Map、绿色 Spawn、黄色 Loot、红色 AI、青色 Extraction、紫色 POI。FAWE 当前选区会按当前编辑工具显示对应颜色的加粗边框；已登记区域会持续显示同类颜色边框。边框会投影到管理员当前高度，因此全高度选区也不会只出现在世界底部和建筑上限。预览半径内的所有地图对象都由硬依赖 DecentHolograms 显示个人标签；选中对象显示完整属性，ERROR 对象显示原因，其余对象显示类型、ID 和关键属性。标签按选中、错误、普通的顺序排列并受独立数量上限保护。所有悬浮文字只由 DecentHolograms 创建，HUD 和粒子仍由 Paper 原生能力渲染。

编辑器支持“看着地图点一下就改”：可视范围内的点位会创建仅当前管理员可见的隐形交互标记。左键标记会选择对象；潜行时右键会打开该对象的属性菜单。菜单中的按钮会修改启用状态、等级、物资组合、容器类型、密度、危险度、生物数量、撤离方式等常用属性；删除按钮只会创建限时确认请求，不会直接删除。菜单物品、快捷栏换位、Shift 点击、数字键和拖拽全部被保护，不能把编辑器按钮移动走。

管理员临时掉线时，个人 DecentHolograms 标签和交互标记会立即清理，但未保存草稿会在本次服务端运行期间留在内存中。主动退出编辑模式时，EscapeFromCraftov 还会同步关闭编辑器 TrMenu，并取消尚未确认的危险操作。重连后再次进入同一张地图会恢复草稿；存在未保存修改时不能切换到另一张地图。服务端重启前仍应执行保存，未保存草稿不会被伪装成持久版本。

`/craftov editor validate` 会把本次验证报告绑定到当前草稿。带对象 ID 的 ERROR 会在对应位置绘制红色叉号，并通过个人悬浮文字列出原因；聊天中的对象 ID 可以点击并安全传送到问题附近。Spawn 还会显示出生朝向箭头。任何地图修改、撤销或重做都会清除旧报告，避免管理员继续看到已经过时的错误标记。

### 3.6 命令大全（每个可执行命令都有独立说明）

| 命令 | 这条命令是做什么的 | 示例 |
| --- | --- | --- |
| `/play` | 使用大厅默认地图加入匹配队列；匹配成功后会被送进一个已经准备好的 Game 房间。 | `/play` |
| `/play <map>` | 指定地图加入匹配队列；Tab 会列出 Lobby 常驻地图和在线 Game 房间实际公布的地图。 | `/play factory` |
| `/leavequeue` | 取消自己尚未完成的匹配排队，不会退出已经开始的行动。 | `/leavequeue` |
| `/lobby` | 打开大厅主菜单，进入游戏、物资仓库、设置和装扮。 | `/lobby` |
| `/menu` | `/lobby` 的快捷别名，作用完全相同。 | `/menu` |
| `/lobby warehouse` | 打开物资仓库与下一局出战栏；点击物品可在两处间移动。 | `/lobby warehouse` |
| `/lobby settings` | 打开大厅玩家可见性和匹配提示设置。 | `/lobby settings` |
| `/lobby cosmetics` | 打开外观收藏并查看当前装备。 | `/lobby cosmetics` |
| `/lobby setting toggle <setting>` | TrMenu 设置按钮使用的动作命令；重新检查玩家数据后保存开关，支持 `visibility` 和 `queue_messages` Tab 补全。 | `/lobby setting toggle visibility` |
| `/lobby cosmetic equip <index>` | TrMenu 外观按钮使用的动作命令；按当前目录编号装备外观。 | `/lobby cosmetic equip 0` |
| `/lobby cosmetic unequip` | 卸下当前大厅外观。 | `/lobby cosmetic unequip` |
| `/lobby warehouse page <previous\|next>` | TrMenu 仓库上一页/下一页动作；页码会被限制在有效范围。 | `/lobby warehouse page next` |
| `/lobby warehouse move <0-35>` | TrMenu 仓库格子动作；重新读取当前页面并用 Hibernate 事务移动物品。 | `/lobby warehouse move 9` |
| `/craftov` | 打开 EscapeFromCraftov 玩家帮助。 | `/craftov` |
| `/craftov help` | 查看玩家可用命令，以及每条命令的用途。 | `/craftov help` |
| `/craftov help admin` | 查看地图、编辑器、房间和诊断命令。 | `/craftov help admin` |
| `/craftov tutorial` | 用玩家能看懂的方式说明一局行动怎么玩。 | `/craftov tutorial` |
| `/craftov info` | 查看服务端版本、已加载模块和当前运行计数。 | `/craftov info` |
| `/craftov profile` | 查看自己的等级、余额和资料状态。 | `/craftov profile` |
| `/craftov map import <source>` | 后台导入普通 Minecraft 世界或完整地图包，地图 ID 默认使用来源文件夹名。Tab 会列出可导入目录。 | `/craftov map import factory` |
| `/craftov map import <source> <target>` | 后台导入地图并指定新的地图 ID，适合同一地形的不同玩法版本。 | `/craftov map import factory factory_night` |
| `/craftov map create <id> <world>` | 高级兼容命令：登记已加载世界并从零制作地图；普通流程不需要它。 | `/craftov map create prototype world` |
| `/craftov map list` | 列出本服登记的地图、版本和启用状态。 | `/craftov map list` |
| `/craftov map info <map>` | 查看指定地图的版本、点位数量和启用状态。 | `/craftov map info factory` |
| `/craftov map preview <map>` | 进入只对当前管理员显示的地图预览与高级编辑模式。 | `/craftov map preview factory` |
| `/craftov map enable <map>` | 验证指定地图，并允许它创建新房间。 | `/craftov map enable factory` |
| `/craftov map disable <map>` | 禁止指定地图创建新房间，不会修改已经运行的房间快照。 | `/craftov map disable factory` |
| `/craftov editor` | 进入当前世界对应的地图编辑模式；再次执行会退出。没有未保存修改时可直接退出，有未保存修改时会要求先保存或明确丢弃。 | `/craftov editor` |
| `/craftov editor enter <map>` | 进入指定地图的独立编辑会话。 | `/craftov editor enter factory` |
| `/craftov editor exit` | 保存状态不变地退出编辑；有未保存修改时会阻止误退出。 | `/craftov editor exit` |
| `/craftov editor exit discard` | 明确丢弃当前未保存草稿并退出编辑。 | `/craftov editor exit discard` |
| `/craftov editor status` | 查看 revision、当前工具、选中对象、保存状态和预览半径。 | `/craftov editor status` |
| `/craftov editor help` | 查看编辑器内的简短操作帮助。 | `/craftov editor help` |
| `/craftov editor menu` | 打开不可拖动的交互式编辑工具栏。 | `/craftov editor menu` |
| `/craftov editor property open` | 打开当前选中对象的 TrMenu 动态属性页。 | `/craftov editor property open` |
| `/craftov editor property <action> <forward\|reverse>` | TrMenu 属性按钮动作；重新检查编辑会话、对象类型和 LuckPerms 权限后修改草稿。 | `/craftov editor property slot_a forward` |
| `/craftov ui loot take <0-44>` | TrMenu Loot 格子动作；重新检查房间、距离、共享槽位和背包容量后取物。 | `/craftov ui loot take 0` |
| `/craftov ui loot page <previous\|next>` | TrMenu Loot 翻页动作；只对当前有效搜索会话生效。 | `/craftov ui loot page next` |
| `/craftov editor wand` | 获取左右键选区工具，用来框选边界和区域。 | `/craftov editor wand` |
| `/craftov editor map bounds` | 把当前选区设置为可游玩地图边界。 | `/craftov editor map bounds` |
| `/craftov editor tool <tool>` | 切换当前编辑工具；后续的快捷交互和预览会以这个工具为准。 | `/craftov editor tool loot` |
| `/craftov editor spawn create` | 在当前位置创建出生点，并自动生成 ID。 | `/craftov editor spawn create` |
| `/craftov editor spawn create <id>` | 在当前位置创建使用指定 ID 的出生点。 | `/craftov editor spawn create spawn_north` |
| `/craftov editor spawn region create <id>` | 把当前选区创建成可随机落点的出生区域；每次进入行动最多检查 24 个候选，只会使用脚下可站且身体两格无阻挡的位置。 | `/craftov editor spawn region create spawn_west` |
| `/craftov editor loot create` | 在当前位置创建使用默认掉落表的虚拟物资点，并自动生成 ID。 | `/craftov editor loot create` |
| `/craftov editor loot create <id>` | 在当前位置创建使用默认掉落表和指定 ID 的虚拟物资点。 | `/craftov editor loot create warehouse` |
| `/craftov editor loot create <id> <loot-table>` | 在当前位置创建虚拟物资点并指定掉落表。 | `/craftov editor loot create warehouse debug_common` |
| `/craftov editor loot bind` | 把准星指向的现有箱子或方块登记为物资点。 | `/craftov editor loot bind` |
| `/craftov editor spawn team <team>` | 修改出生点队伍；`players`/`any` 对所有队伍开放，`red` 对应队伍 1，`blue` 对应队伍 2，也支持 `team3`、`team_3` 等数字队伍。专属出生点优先于通用点。 | `/craftov editor spawn team red` |
| `/craftov editor spawn weight <weight>` | 调整选中出生点在当前队伍候选池内被选中的相对权重。 | `/craftov editor spawn weight 1.5` |
| `/craftov editor spawn enable` | 启用选中的出生点，不删除它。 | `/craftov editor spawn enable` |
| `/craftov editor spawn disable` | 停用选中的出生点，不删除它。 | `/craftov editor spawn disable` |
| `/craftov editor loot type <container-type>` | 把选中物资点设置为箱子、木桶、潜影盒或虚拟点。 | `/craftov editor loot type barrel` |
| `/craftov editor loot table <loot-table>` | 更换选中物资点使用的掉落表；Tab 会列出现有掉落表。 | `/craftov editor loot table debug_common` |
| `/craftov editor loot tier <1-4>` | 调整选中物资点的稀有度层级。 | `/craftov editor loot tier 3` |
| `/craftov editor loot enable` | 启用选中的物资点，不删除它。 | `/craftov editor loot enable` |
| `/craftov editor loot disable` | 停用选中的物资点，不删除它。 | `/craftov editor loot disable` |
| `/craftov editor lootzone create <id> [loot-table]` | 把当前选区创建成物资区域；省略掉落表时自动使用当前默认表。 | `/craftov editor lootzone create factory_high` |
| `/craftov editor lootzone table <loot-table>` | 更换选中物资区域的掉落表。 | `/craftov editor lootzone table debug_armory` |
| `/craftov editor lootzone tier <1-4>` | 设置选中物资区域的稀有度层级。 | `/craftov editor lootzone tier 4` |
| `/craftov editor lootzone density <value>` | 调整选中物资区域生成点的密度系数。 | `/craftov editor lootzone density 1.5` |
| `/craftov editor lootzone danger <0-1>` | 设置选中物资区域的危险系数，供风险与收益平衡使用。 | `/craftov editor lootzone danger 0.75` |
| `/craftov editor lootzone enable` | 启用选中的物资区域。 | `/craftov editor lootzone enable` |
| `/craftov editor lootzone disable` | 停用选中的物资区域。 | `/craftov editor lootzone disable` |
| `/craftov editor ai zone create <id>` | 把当前选区登记成未来生成 Minecraft 生物威胁的区域。 | `/craftov editor ai zone create yard_mobs` |
| `/craftov editor ai type <type>` | 设置选中生物区域的生物类型标签。 | `/craftov editor ai type guardian` |
| `/craftov editor ai minimum <count>` | 设置选中生物区域的最低生物数量。 | `/craftov editor ai minimum 2` |
| `/craftov editor ai maximum <count>` | 设置选中生物区域的最高生物数量。 | `/craftov editor ai maximum 5` |
| `/craftov editor ai respawn <seconds>` | 设置生物区域重新生成的等待时间。 | `/craftov editor ai respawn 60` |
| `/craftov editor ai difficulty <value>` | 设置生物区域的难度系数。 | `/craftov editor ai difficulty 1.25` |
| `/craftov editor ai team <team>` | 设置生物区域的阵营标签。 | `/craftov editor ai team neutral` |
| `/craftov editor ai enable` | 启用选中的生物区域。 | `/craftov editor ai enable` |
| `/craftov editor ai disable` | 停用选中的生物区域。 | `/craftov editor ai disable` |
| `/craftov editor extraction create <id>` | 把当前选区创建成普通撤离点。 | `/craftov editor extraction create north_gate` |
| `/craftov editor extraction create <id> normal` | 明确创建无需额外条件的普通撤离点。 | `/craftov editor extraction create north_gate normal` |
| `/craftov editor extraction create <id> item <item-id>` | 创建需要携带指定钥匙或物品的条件撤离点。 | `/craftov editor extraction create bunker item extraction_key` |
| `/craftov editor extraction create <id> paid <cost>` | 创建需要支付指定金币的撤离点。 | `/craftov editor extraction create wagon paid 500` |
| `/craftov editor extraction create <id> timed <seconds>` | 创建行动开始指定秒数后才开放的撤离点。 | `/craftov editor extraction create tunnel timed 600` |
| `/craftov editor extraction name <name>` | 修改选中撤离点在游戏内显示的名称。 | `/craftov editor extraction name 北门出口` |
| `/craftov editor extraction duration <seconds>` | 修改选中撤离点的引导倒计时。 | `/craftov editor extraction duration 15` |
| `/craftov editor extraction type <normal、item、paid 或 timed>` | 修改已有条件撤离点的类型；如果当前是普通撤离，请直接使用带 value 的下一条形式。 | `/craftov editor extraction type normal` |
| `/craftov editor extraction type <type> <value>` | 一次性修改条件类型和条件值，适合把普通撤离点直接改成钥匙、付费或计时撤离。 | `/craftov editor extraction type paid 500` |
| `/craftov editor extraction condition <value>` | 设置钥匙 ID、金币数量或开放秒数等条件值。 | `/craftov editor extraction condition extraction_key` |
| `/craftov editor extraction enable` | 启用选中的撤离点。 | `/craftov editor extraction enable` |
| `/craftov editor extraction disable` | 停用选中的撤离点。 | `/craftov editor extraction disable` |
| `/craftov editor poi create <id>` | 把当前选区创建成默认 Tier 2 的兴趣点。 | `/craftov editor poi create central_factory` |
| `/craftov editor poi create <id> <tier>` | 创建兴趣点并设置 1 至 4 的价值和危险等级。 | `/craftov editor poi create central_factory 3` |
| `/craftov editor poi name <name>` | 修改选中兴趣点的显示名称。 | `/craftov editor poi name 中央仓库` |
| `/craftov editor poi tier <1-4>` | 修改选中兴趣点的价值层级。 | `/craftov editor poi tier 4` |
| `/craftov editor poi enable` | 启用选中的兴趣点。 | `/craftov editor poi enable` |
| `/craftov editor poi disable` | 停用选中的兴趣点。 | `/craftov editor poi disable` |
| `/craftov editor select <id>` | 选择预览范围内的对象；对象 ID 支持 Tab 补全。 | `/craftov editor select extract_north` |
| `/craftov editor info [id]` | 查看准星指向的对象；填写 ID 时查看指定对象，并自动将它设为当前选择。 | `/craftov editor info loot_3` |
| `/craftov editor tp [id]` | 安全传送到准星指向或指定对象附近；不填 ID 时使用当前选择。 | `/craftov editor tp extract_north` |
| `/craftov editor nearby` | 按距离从近到远列出预览半径内对象，点击聊天行可以选择。 | `/craftov editor nearby` |
| `/craftov editor move` | 把当前选中对象移动到管理员站立位置。 | `/craftov editor move` |
| `/craftov editor clone <new-id>` | 在当前位置复制选中对象，并使用新的 ID。 | `/craftov editor clone extract_east` |
| `/craftov editor copy` | 把当前选中对象放入本次编辑会话的剪贴板。 | `/craftov editor copy` |
| `/craftov editor paste <new-id>` | 把剪贴板对象按当前位置粘贴为新的对象。 | `/craftov editor paste loot_002` |
| `/craftov editor delete` | 删除当前选中对象。 | `/craftov editor delete` |
| `/craftov editor delete <id>` | 删除指定对象；附近对象 ID 支持 Tab 补全。 | `/craftov editor delete loot_3` |
| `/craftov editor delete type <type>` | 请求批量删除一种对象，必须再执行 `confirm`。 | `/craftov editor delete type loot` |
| `/craftov editor batch enable [type]` | 把当前 WorldEdit/选区棒选区内的对象批量启用；可填写对象类型过滤。 | `/craftov editor batch enable loot` |
| `/craftov editor batch disable [type]` | 把当前选区内的对象批量停用，不删除配置。 | `/craftov editor batch disable ai` |
| `/craftov editor batch table <loot-table>` | 把选区内的 Loot 点和 Loot 区域统一换成指定物资组合；Tab 自动列出现有组合。 | `/craftov editor batch table debug_armory` |
| `/craftov editor batch tier <1-4>` | 统一设置选区内 Loot、Loot 区域和 POI 的等级。 | `/craftov editor batch tier 3` |
| `/craftov editor batch delete [type]` | 请求删除选区内对象；可按类型过滤，必须再执行 `confirm`。 | `/craftov editor batch delete loot` |
| `/craftov editor confirm` | 确认 15 秒内发起的危险批量删除操作。 | `/craftov editor confirm` |
| `/craftov editor undo` | 撤销当前编辑会话的上一步修改。 | `/craftov editor undo` |
| `/craftov editor redo` | 重做刚刚撤销的修改。 | `/craftov editor redo` |
| `/craftov editor show all` | 显示所有类型的个人预览标记。 | `/craftov editor show all` |
| `/craftov editor show bounds` | 显示当前地图边界，即使当前工具不是地图工具。 | `/craftov editor show bounds` |
| `/craftov editor show <type>` | 显示一种对象的个人预览标记。 | `/craftov editor show extraction` |
| `/craftov editor hide all` | 隐藏所有个人预览标记，不会删除地图数据。 | `/craftov editor hide all` |
| `/craftov editor hide bounds` | 隐藏地图边界粒子，不会修改地图边界。 | `/craftov editor hide bounds` |
| `/craftov editor hide <type>` | 隐藏一种对象的个人预览标记。 | `/craftov editor hide ai` |
| `/craftov editor radius <blocks>` | 设置 16 至 512 格的个人预览半径。 | `/craftov editor radius 128` |
| `/craftov editor validate` | 检查边界、距离、点位分布和引用，并输出质量分。 | `/craftov editor validate` |
| `/craftov editor save` | 验证并原子保存草稿，生成新的地图 revision。 | `/craftov editor save` |
| `/craftov editor history` | 查看当前地图可回滚的 revision。 | `/craftov editor history` |
| `/craftov editor rollback <revision>` | 恢复指定 revision，并把恢复结果保存为新 revision。 | `/craftov editor rollback 3` |
| `/craftov game create` | 使用默认地图创建独立房间并复制一份世界。 | `/craftov game create` |
| `/craftov game create <map>` | 使用指定已启用地图创建独立房间并复制一份世界。 | `/craftov game create factory` |
| `/craftov game list` | 列出本 Game 服务端的所有房间，并显示地图、状态、人数与世界副本是否就绪。 | `/craftov game list` |
| `/craftov game start <id>` | 在地图副本已经加载完成后开始指定 WAITING 房间；副本仍在复制时会提示稍后重试。房间 ID 可直接按 Tab 选择。 | `/craftov game start a1b2c3` |
| `/craftov game stop <id>` | 结束指定房间，结算仍在场玩家并销毁实例世界。房间 ID 可直接按 Tab 选择。 | `/craftov game stop a1b2c3` |
| `/craftov debug` | 查看管理员诊断信息，不修改任何房间。 | `/craftov debug` |
| `/craftov debug loot <table>` | 测试指定掉落表的一次结果，不影响正式房间。 | `/craftov debug loot debug_common` |

上述命令树由 Paper Brigadier 注册。所有固定子命令会自动补全；地图包目录、地图、世界、房间、附近对象、对象类型、LootTable、条件物品和 revision 会从实时数据生成候选。需要新名字的地图 ID 和对象 ID 也会给出一个当前可用的默认候选；数值参数会给出常用预设，同时仍允许输入范围内的自定义值。导入地图时直接在 `source` 后按 Tab，就能从 `imports/`、模板目录和服务器世界目录选择可导入文件夹，不需要手工复制长路径。

### 3.7 权限

管理员默认使用 Bukkit OP，也可以用 LuckPerms 分配：

```text
craftov.admin
craftov.command.info
craftov.lobby.play
craftov.editor
craftov.editor.map
craftov.editor.spawn
craftov.editor.loot
craftov.editor.ai
craftov.editor.extraction
craftov.editor.poi
craftov.editor.delete
craftov.editor.save
craftov.editor.rollback
```

`craftov.command.info` 与 `craftov.lobby.play` 默认允许普通玩家；管理和编辑节点默认仅 OP。
LuckPerms 中明确设置的 `true`/`false` 优先，没有设置的节点会回退到这些 Bukkit 默认值。
玩家不需要编辑器权限即可排队、搜索 Loot 和撤离。

### 3.8 配置重点

```yaml
combat:
  friendly-fire: false
  attribution-seconds: 15
scoreboard:
  enabled: true
  refresh-ticks: 10
inventory:
  loot-container-size: 27
map-editor:
  retained-revisions: 3
  default-visibility-radius: 96
  visualization:
    refresh-ticks: 10
    maximum-objects-per-frame: 160
    maximum-holograms-per-player: 32
```

`scoreboard.refresh-ticks` 越小，信息越及时，但更新越频繁。`inventory.loot-container-size` 只能是 9、18、27、36、45 或 54。`map-editor.default-visibility-radius` 控制每位管理员看到标记的默认距离，`maximum-objects-per-frame` 限制单次粒子和对象查询数量，`maximum-holograms-per-player` 单独限制个人全息标签数量。地图运行中不建议修改影响规则的配置；安全做法是让它在下一局生效。

### 3.9 代理和数据库排障

- 玩家能进 Lobby 但匹配后回到 Lobby：检查 Redis 是否可读、Game 快照是否新鲜、`server.id` 是否重复。
- 传送目标不存在：检查代理中的 backend 名称是否和 Game `server.proxy-name` 完全一致，大小写和连字符都不能不同。
- Game 快照里有 `craftov-01` 但代理仍报找不到：这是代理配置问题，标准 BungeeCord 插件消息没有查询 backend 是否存在的 API，应看代理日志并手动执行 `/server craftov-01` 验证。
- 插件拒绝启动：先看 Java 25、Paper 1.21.11 和 PostgreSQL 连接，再看 Hikari 日志；Game/Lobby 都强依赖 PostgreSQL。
- 仓库打不开：确认两个服务端连接同一个数据库/schema，并检查 Hibernate `validate` 是否与当前表结构一致。
- 地图实例创建失败：确认地图世界存在、模板目录可读、`game.allow-flat-fallback` 是否只在本地开发开启。
- 撤离结算失败：不要强制 `/server lobby`。系统会锁定倒计时结束时的背包快照并每 5 秒自动重试；检查 PostgreSQL 和 `craftov_player_items` / `craftov_inventory_settlements` 表后恢复服务即可。

## 4. 开发者指南

### 4.1 模块结构

```text
api    -> 领域接口、不可变记录、事件和权限
core   -> Hibernate ORM、Hikari、Redis、领域服务、物品/掉落/游戏实现
game   -> Paper Game 适配、世界副本、地图编辑器、TrMenu Loot、PvP、HUD
lobby  -> Paper Lobby 适配、匹配、TrMenu 仓库/出战、设置和装扮
```

Game 和 Lobby 是两个独立进程。`core` 可以使用 Paper glue 下的有限 Bukkit 类型来转换物品，但领域规则不依赖具体 GUI 表现。

### 4.2 PostgreSQL 物品闭环

```text
WAREHOUSE  --大厅点击-->  LOADOUT
LOADOUT   --进服原子领取--> CRAFTOV(gameId)
CRAFTOV      --原生背包/TrMenu搜索--> 玩家实际携带物
CRAFTOV      --撤离事务--> WAREHOUSE
CRAFTOV      --死亡/退出/超时--> 删除（损失）
```

Hibernate 实体：

- `craftov_player_items`：一个玩家的一件物品栈，位置为 `WAREHOUSE`、`LOADOUT` 或 `CRAFTOV`。
- `craftov_inventory_settlements`：`gameId + playerId` 的幂等终态标记，防止重复撤离或死亡同时写入。
- 既有档案、设置、装扮实体：同一 Hibernate `SessionFactory` 和 HikariCP 池。

仓库移动和领取在事务中完成；结算先删除 CRAFTOV 行，再按实际背包内容写入新的 WAREHOUSE 行。不能从 Bukkit 物品名称推断所有权，必须读取 `escapefromcraftov:item_id`、`escapefromcraftov:instance_id` 等 PDC 标记。

### 4.3 Loot 运行时流程

地图 revision 编译为 `CraftovMapRuntime` 时，会捕获 `CraftovLootPoint`：位置、容器类型、LootTable、Tier 和是否虚拟。创建游戏后这个列表不可变，管理员保存新地图不会影响正在运行的对局。

`CraftovLootListener` 使用 `(gameId, lootPointId)` 作为公共容器键：

1. 玩家在正确实例、正确世界、`RUNNING` 状态下右键物资点。
2. 第一次打开调用 `LootService.roll`，生成带 PDC 的 Bukkit `ItemStack`，并保存到本局共享容器状态。
3. TrMenu 通过 `CustomItemSourceEvent` 显示当前槽位；已经被拿走的物品不会重新生成。
4. 点击物品会调用受保护取物动作，再次检查本局、玩家、Loot 点距离、槽位和完整背包容量；成功后才从共享状态移除并放入本局背包。
5. TrMenu 隐藏真实玩家物品栏且没有自由存入槽，玩家不能拖拽、Shift 点击或数字键把自己的物品放进公共容器。
6. `CraftovEndedEvent` 关闭相关视图并清理本局容器缓存。

### 4.4 事件时序与线程边界

撤离不是直接返回：

```text
ExtractionService 完成引导
  -> Session: EXTRACTING -> SETTLING
  -> PlayerExtractionReadyEvent
  -> 主线程锁定一次 Bukkit 背包快照并保护玩家
  -> 异步 Hibernate settleExtraction
  -> 失败时以同一快照自动重试
  -> 既有 EXTRACTED 视为幂等成功；既有 LOST 视为终态冲突
  -> Session: SETTLING -> COMPLETED
  -> 成功后发布 PlayerExtractedEvent
  -> GameManager 移除玩家
  -> Bridge 清空临时背包并送回 Lobby
```

`PlayerExtractedEvent` 的语义是“已经成功入库”，不能在数据库事务前发布。`SETTLING` 会话不可由离开区域取消，掉线和 `CraftovEndedEvent` 也不会同时对它执行死亡结算；对局世界会等待这笔结算形成终态。`CraftovSettlementResult` 返回数据库中已经存在的真实 `EXTRACTED` / `LOST` 结果，因此提交成功但客户端回调失败后的重试不会重复入库。死亡、普通退出和超时同样必须先 `settleDeath`。Paper 世界、实体、Inventory API 只在主线程读取或修改；Hibernate/Redis 使用 `CraftovContext.ioExecutor()`，回调通过 `CraftovScheduler.runSync` 回到主线程。

### 4.5 Minecraft PvP 规则

`GameCombatListener` 统一处理 `EntityDamageEvent`、`EntityDamageByEntityEvent` 和 `PlayerDeathEvent`：

- 解析 Player、箭/弩等 Projectile 的 shooter。
- 取消跨实例伤害和非 RUNNING 阶段伤害。
- 用 `CraftovPlayer.teamId` 执行队伍友伤规则。
- 记录短时间有效攻击者，死亡时发送本局击杀提示。
- `PlayerDeathEvent` 强制保留经验规则由服务器设定，携带物品按死亡损失处理。

新增战斗类型时不要复制一套武器系统；只要让 Bukkit 事件进入这条边界，Minecraft 原生伤害就会自动遵守实例和队伍规则。

### 4.6 HUD 和展示层约定

`CraftovSidebarService` 为每个玩家保存旧 Scoreboard，并用固定 Team entry 更新内容，关闭服务或离开编辑/游戏后恢复旧板。展示层职责如下：

| 表现 | 用途 |
| --- | --- |
| Sidebar | 持续数据：地图、阶段、时间、玩家、队伍、背包、价值、击杀、撤离。 |
| BossBar | 只在撤离引导中显示进度。 |
| Action Bar | 当前工具和短状态。 |
| Title/SubTitle | 进入编辑器、进入行动、撤离成功等阶段切换。 |
| DecentHolograms | 编辑器附近对象标签、选中详情和验证错误，按管理员个人可见。EscapeFromCraftov 不创建 TextDisplay/ArmorStand 全息图。 |
| TrMenu Inventory GUI | Lobby 主菜单、仓库、出战栏、设置、外观、Loot 搜索和编辑器工具栏/对象属性。 |
| Tab Header/Footer | Lobby 显示大厅；Game 动态显示当前地图、本局人数和“只显示本房间玩家”。 |
| Tab 玩家名 | Game 房间内显示 `[地图] 队 N · 玩家名`，不同房间的玩家不会出现在彼此列表。 |
| Chat | 房间聊天只发送给同一 `game:<房间>` 隔离组，并在游戏聊天前显示“[本局]”；加入、退出、死亡和击杀提示同样不会泄漏到其他房间。`/msg`、`/tell`、`/w` 只能联系同房间玩家，`/me` 只在本房间广播。 |

不要在玩家可见文案中输出类名、数据库表名或 `PostgreSQL` 等实现细节；技术细节只写在日志和本文开发者章节。

硬依赖集成遵循适配器边界：LuckPerms 用于权限，WorldEdit 用于读取选区，DecentHolograms 是唯一全息图实现，PlaceholderAPI 用于对外占位符，TrMenu 负责全部 Inventory GUI。动态物品由 EscapeFromCraftov 注入 TrMenu，点击后仍由 EscapeFromCraftov 执行权限、会话和事务校验。缺失、版本不匹配或适配器初始化失败会阻止加载；ProtocolLib、Citizens、MythicMobs、ItemsAdder、Oraxen、FAWE 与 Vault 的评估和暂不硬依赖原因见 README。

PlaceholderAPI 当前注册的占位符包括 `map`、`game_id`、`game_state`、`player_state`、`team`、`money`、
`level`、`experience`、`experience_to_next_level`、`alive_players`、`loot_value`、`remaining_time` 和 `extracted`。`loot_value` 读取本局背包内物资的真实估值，
`level`、`experience` 和 `experience_to_next_level` 分别读取行动等级、累计历练和距离下一级的历练；`remaining_time` 读取当前行动计时，`extracted` 读取当前玩家的撤离状态；不在行动中或无法取得真实状态时返回空值，
不显示伪造的默认数字。

### 4.7 构建和测试

```bash
./gradlew clean build
./gradlew test
git diff --check
```

当前核心测试覆盖档案、经济、地图验证、地图快照、撤离状态、掉落表、匹配和网络快照。Paper 事件、真实 PostgreSQL 和代理连接仍需要在测试服做集成验证；单元测试不能替代真实 Paper 1.21.11 + PostgreSQL + Redis 联调。

### 4.8 当前限制与下一阶段

- 正式地图必须由管理员人工配置；运行时不提供自动扫描或自动生成点位命令。
- AI Zone 已能保存和显示，但完整生物生成、寻路和 PvE 行为尚未完成。
- 仓库、出战栏、Loot、撤离入库和死亡损失已经形成闭环；商店、交易行、安全箱和跨局保险属于规划功能。
- `PlayerExtractionReadyEvent` 之后如果数据库不可用，玩家会留在 Game，不会被错误送回大厅；恢复数据库后应重新触发结算，而不是清理玩家背包。
- 代理 backend 是否真实注册无法通过标准 BungeeCord Connect 消息查询，必须由部署配置和代理日志共同验证。

最值得继续开发的实际玩法是 AI Zone 的 Minecraft 生物行为：复用现有地图区域、Loot 风险和 Sidebar，不引入枪械或外部 AI API。
