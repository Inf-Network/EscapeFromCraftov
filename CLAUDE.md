# CLAUDE.md

Craftov：Paper 1.21.11 上的搜打撤（extraction shooter）插件，玩法对标三角洲行动 / 逃离塔科夫，但所有名称、地图、贴图、音效都是原创（枪模来自 CC0 的 Quaternius Ultimate Gun Pack）。依赖 CraftEngine 26.9.2（资源包托管）和 BetterHud 2.2.0-SNAPSHOT-449（HUD）。玩法和指令的完整说明见 `README.md`，这里只记开发需要知道的东西。

和用户交流用中文。

## 开发进度

主分支 `main`（推到 `origin/main`）。旧版本保存在 `old` 分支；`craftov` 分支已并入 `main`，不再使用。这是对原先无法游玩的插件的完全重写，包名 `net.infnetwork.snowball.craftov`。

### 已完成并推送

README「玩法一览」表里列出的全部系统都已实现：大厅与战术终端、配装 / 战备值 / 三档难度、单三排匹配（每张图每档一个队列，可中途加入）、入场运镜、11 把枪（弹道、后坐、扩散、射击模式）、弹药穿透与护甲、逐格搜索、分部位伤病与医疗动画、体力负重、两次倒地与救援、六种撤离点、钥匙门 / 电闸 / 碎玻璃 / 投掷物、安全箱、商人、交易行（虚拟上架 + 系统买家）、干员技能、排位、收藏室、护航、配装方案、免费补给、断线重连、地图编辑器、测试地图「试验场」与测试军械库、个人独立靶场、近战槽与刀、静步、持枪姿势（第一 / 第三人称）、分层枪声、弹孔与命中特效、压制效果、敌兵士兵外观与尸体、改枪台五个配件位、战术手电 / 爆闪、击杀回放、每日任务、夜战与随机事件、持盾兵 / 敌对干员小队 / 侦察无人机、装备保险、藏身处、悬赏、赛季通行证、新手指引、每日数据备份。

最近一次提交：`75f7382b feat: step-by-step guide for new players`。

### 只做过 bot / 静态验证，还需要真人进游戏确认

- 画面与手感：持枪姿势、换弹动画、开镜、手电和爆闪的可见度。
- 持盾兵是否正确挡住正面子弹。
- 敌对干员小队在箱子之间搜刮的表现。

### 计划中但未开始（等用户确认再做）

- 商人剧情任务线。
- 基础反作弊。

## 构建与部署

```bash
./gradlew jar                 # build/libs/Craftov-1.0.0.jar（JDK 25 工具链，产出 Java 21 字节码）
./gradlew deployLocal         # 构建并复制到 server/run/plugins/Craftov.jar
python3 tools/art/build.py    # 重新生成贴图、模型、音效、BetterHud 布局
```

- 开发在远程服务器上（这台机器的 `java` 就是 25，`./gradlew` 直接能用），真人测试服在用户的 Mac 上，见下面「测试服运维」。
- 没有单元测试目录；验证靠编译 + 部署 + `tools/bot/` 里的 mineflayer 脚本（`node test.js <名字> [场景]`，以及 fire / meds / revive / squad / doors / econ 等专项脚本）。bot 只能连这台机器上的本地测试服（Mac 的 25565 从这边连不到），用离线模式连 `127.0.0.1`，跑之前要临时加白名单，名字用随机的，否则会重连进旧对局。
- 改完 `tools/art/build.py` 后，它每次都会重新编码音效、产生无意义的 diff：提交前用 `git checkout -- src/main/resources/pack/craftov/resourcepack/assets/*/sounds/` 还原没改过的音效。
- 生成的 BetterHud yml 和资源包文件不要手改，改 `tools/art/` 里的生成器，否则下次构建会被覆盖。

## 测试服运维

真人测试在用户的 Mac 上：测试服是 Mac 上 `~/Documents/projects/plugins/EscapeFromCraftov/server/run`（被 gitignore，由 `server/setup.sh` 生成）。这台服务器通过 mac-bridge（`ssh mac`，只能运行 Mac 上的门卫脚本）和门卫扩展 `tools/mac/craftov` 操作它，命令一律走 `tools/mac/mac`：

- **部署**：`tools/mac/mac deploy` = 把 `main` 的提交同步到 Mac 项目（git bundle 走 ssh，Mac 不联网）+ 在这边 `./gradlew jar` 后推过去 + 重启并等到 `Done (`。只换插件：`tools/mac/mac jar` 再 `tools/mac/mac restart`。jar 用改名的方式替换，运行中的服务器不受影响。
- **开关服 / 状态**：`tools/mac/mac start|stop|restart|status`。扩展会处理 Mac 上的 `run-forever.sh` 重启循环（5 秒后自动拉起）和用户手动开的 `screen -S craftov`：关服先结束循环再 RCON `stop`，等 `paper.jar` 退出。不要用 `ssh mac` 之外的方式去动 Mac 上的进程。
- **控制台命令**：`tools/mac/mac rcon <命令>`，命令原样传过去（JSON 引号保留）。
- **日志**：`tools/mac/mac log [行数]`（`logs/latest.log`）；开服失败时 Paper 还没打开日志前的报错（坏 jar、java 起不来）在 `tools/mac/mac consolelog [行数]`；客户端日志 `tools/mac/mac clientlog [行数]`，排查资源包下载失败（`download_failed`）时看。
- **白名单**：`online-mode=false`，离线 UUID 由名字的**确切大小写**算出，`whitelist add` 必须用玩家真实的大小写（看日志里的 `UUID of player Xxx`）。用户的名字是 `Snowball_233`。
- **资源包地址**：`plugins/CraftEngine/config.yml` 的 `self-host.ip`，资源包托管在 MC 同一端口 25565。改用 `tools/mac/mac packip <IP>`（服务器运行中会自动 `ce reload all`）。平时用 Mac 的局域网 IP，`start.sh` 每次开服会自动刷新私有网段的地址；用户要让外网玩家进来时会告诉你公网 IP（"换ip x.x.x.x"），公网 IP 不会被自动覆盖，外网要能连还需要用户把 25565 映射到 Mac。Mac 当前的公网 IP 可以从这台机器 `who` 里用户 ssh 进来的地址看到。
- **服务器上的 `plugins/Craftov/items.yml`、`loot.yml` 只在缺失时才从 jar 复制**。改了物品后先 `tools/mac/mac yml diff items.yml` 确认服务器那份没有手动改动，再 `tools/mac/mac yml copy items.yml`（自动备份旧的），然后重启。`config.yml` 缺的键会回落到 jar 里的默认值。
- **同步被拒绝**：Mac 项目有未提交改动、或有这边没有的提交时，`sync` 会拒绝而不是覆盖。`tools/mac/mac pull-mac` 把 Mac 的本地分支取到这边的 `refs/mac/*` 查看；确认保住之后用 `tools/mac/mac sync --park <名字>` 把那个分支改名保留，再切到 `main`。
- **扩展本身**：Mac 上的 `~/.local/libexec/remote-gate/craftov` 会在每次 `sync`（包括 `deploy`）之后自动换成同步过去的 `tools/mac/craftov`，所以改扩展只要提交再 `deploy`；没提交时用 `tools/mac/mac selfupdate` 直接推工作区里的版本。替换前新版本必须在 Mac 上跑通 `selfupdate --check`，否则保留旧的，所以**新版本必须保留 `selfupdate` 指令**，不然以后就只能请用户手动装（`ssh mac put craftov < tools/mac/craftov`，用户在 Mac 终端运行 `install -m 755 ~/RemoteShare/craftov ~/.local/libexec/remote-gate/craftov`）。门卫 `~/.local/bin/remote-gate` 不会自动更新，源码在用户的 `SnowballXueQiu/mac-bridge` 仓库，改它要用户手动装。
- **本机无头测试服**（给 bot 用）：在这台机器上 `server/setup.sh --local` 生成 `server/run/`，`server/run/start.sh` 开服；控制台命令用 `cd tools/bot && node -e "require('./rcon')('命令').then(console.log)"`（需要在 `server.properties` 里打开 RCON，密码和 `tools/bot/rcon.js` 一致）。

## 提交规范

- **每做完一个小模块就提交并 `git push origin main`**，不用等用户要求。一个提交只做一件事，每个提交都要能单独编译。不要攒一大堆改动一次提交。
- **提交信息和 PR 里不加任何 Co-Authored-By / AI 署名**（用户明确要求过，优先于默认的署名设置）。
- 格式：英文 Conventional Commits 前缀（`feat:` / `fix:` / `tune:` / `docs:` / `refactor:` / `chore:`），主题行小写、描述玩家能感知到的行为（例如 `feat: bounties on players`、`fix: no flash on an empty gun, and a tactical light you can see`）。正文用几句完整的英文解释做了什么、数值是多少，约 75 列换行。
- 新功能要同步更新 `README.md`（中文，玩法表 / 操作 / 指令 / 配置），可以和功能放在同一个提交里。

## 代码规范

- 结构：`src/main/java/net/infnetwork/snowball/craftov/` 按领域分包（weapon、raid、combat、gui、economy、ai、lobby、hud、map、inventory、item、profile、rank、replay、util……）。每个系统是一个 `XxxService`，在 `CraftovPlugin.onEnable` 里构造（构造参数是 plugin），需要监听事件的加入 `register(...)`（注册顺序对同优先级的处理器有意义），通过 `CraftovPlugin.get()` 或 `plugin.xxx()` 访问其他服务。
- 类用 `final`，字段 `private final`；可空值用 `org.jspecify.annotations.Nullable`，嵌套类型要写成 `Outer.@Nullable Inner`。
- 代码注释和 Javadoc 用英文，写"为什么"；类头一段 Javadoc 说明这个系统的玩法。
- 玩家可见文本全部中文，用 MiniMessage，通过 `util/Text`（`Text.send`、`Text.mm`、`Text.money`、`Text.escape` 转义玩家名）。常用色：金 `#F2C94C`、灰 `#9AA0A6`、红 `#FF4D4D`。货币符号 `◈`。返回"失败原因"的方法约定：成功返回 null，失败返回中文原因字符串。
- 数值放 `config.yml`（用 `plugin.getConfig().getXxx("path", 默认值)` 读，代码里带默认值），物品放 `items.yml`，掉落放 `loot.yml`。新增配置段要同时更新 `src/main/resources/config.yml`。
- 玩家数据在 `profile/Profile`（Gson JSON，原子写、异步单线程保存）；修改后调用 `markDirty` 或 `profiles().edit(...)`。
- 物品构造方式改变时把 `ItemFactory.VERSION` 加一，玩家身上的旧物品会被重建。
- BetterHud 的 placeholder 在异步线程上求值，只能读主线程每 2 tick 生成的 `HudSnapshot`，不能直接读游戏状态。
- 跨世界计算距离前先判断是否同一世界（"Cannot measure distance between worlds" 出现过多次）。

## 关键实现与坑

- **左键开火**：原版客户端只有在"挖方块"时才持续发包，所以在准星前约 3 格只对该玩家放置透明、静音、无碰撞的蛛网（TriggerService "capture cells"，Netty 出站改写在 `util/Nms`），每 tick 挥手 = 扳机按下，`BlockDamageAbortEvent` = 松开。Paper 会把挥手报成 `LEFT_CLICK_AIR`，换弹 / 点门 / 背包操作需要静默窗口防误射。
- **右键开镜**：物品模型 `keybind_down` 条件在客户端切姿势；倍率靠移动速度属性算 FOV，必须立即应用速度，否则开镜卡顿。
- **后坐力**：反射调用 `Entity.forceSetRotation` 做相对旋转，不要用 teleport（会触发大厅虚空检测导致全自动只打一发）。
- **持枪姿势**：`util/Nms` 把装备包里的枪改写成已上弦的弩，其他玩家看到双手端枪。
- **custom_model_data 字符串位**：0 瞄具，1–4 枪口 / 握把 / 战术 / 弹匣，5 "dry"（空仓不出枪口火焰）。
- **换弹动画**：服务端 `setCooldown(craftov:reload)`，物品定义按 `minecraft:cooldown` 做 24 帧 `range_dispatch`。
- **倒地姿势**：`setPose(Pose.SWIMMING, true)` 让所有人看到爬行。
- **AI**：zombie / husk / drowned 身体 + 资源包换皮，Paper Pathfinder；无人机用 Bee 碰撞箱。
- **对局世界**：虚空生成器，每局复制模板文件夹，结束后销毁。控制台 `/tp` 会传到主世界，要用 `execute as X at @s run tp @s ...`。
- `fpview.py <枪id>` 能离线渲染第一 / 第三人称持枪画面，调枪的位置不用进游戏。
- BetterHud 罗盘内部偶尔有 ConcurrentModificationException，来自 BetterHud 自身，无害。
