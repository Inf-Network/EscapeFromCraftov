# CLAUDE.md

Craftov：Paper 1.21.11 上的搜打撤（extraction shooter）插件，玩法对标三角洲行动 / 逃离塔科夫，但所有名称、地图、贴图、音效都是原创（枪模来自 CC0 的 Quaternius Ultimate Gun Pack）。依赖 CraftEngine 26.9.2（资源包托管）和 BetterHud 2.2.0-SNAPSHOT-449（HUD）。玩法和指令的完整说明见 `README.md`，这里只记开发需要知道的东西。

和用户交流用中文。

## 开发进度

主分支 `main`（推到 `origin/main`）。旧版本保存在 `old` 分支；`craftov` 分支已并入 `main`，不再使用。这是对原先无法游玩的插件的完全重写，包名 `net.infnetwork.snowball.craftov`。

### 当前状态（2026-11-22）

所有待办都在 GitHub issue 里（`gh issue list -R Inf-Network/EscapeFromCraftov`），以 issue 为准，这里只记大方向。

**进度看板：置顶的 #72。** 服主说过「我现在都不知道你一直在干什么」：每开始一批、每合并一次、每部署一次都要更新 #72 的正文（现在在做 / 接下来 / 已完成的勾选），并在对话里用几句话主动告诉服主现在在跑什么、跑到哪一步、大概还要多久。后台任务跑很久时也要中途汇报，不要只在全部做完后才说。

- **已修复并合并**：#1 匹配（PR #11）；4 个 P0：#12 试验场（#47）、#13 交易行刷钱（#48）、#14 靶场吞装备 + 关服存档（#49）、#15 爆闪冻 AI（#50）；丢物品 / 存档 / 刷钱 6 个 P1：#16–#21（#54–#59，整合成一条线后一起合并）；工具：#52 截图（#53）、震撼弹截图场景（#65）。
- **进行中（第三批）**：#22 经济数值、#23 运镜卡死、#24 掉线脱战、#26 护甲、#27 刀、#28 倒地救援、#29 击杀归属、#30 AI 伤害、#31 AI 行为、#32 精度后坐、#33 扳机、#34 切枪、#35 命中判定、#36 回放替身、#67 关服作废。分支在 `.claude/worktrees/` 里，按链叠放，最后合进一个整合分支 `integrate/batch3` 再合并。
- **服主反馈待做**：#2 枪声（换 CC0 真实录音）、#3 持枪 / 刀 / 静步 / 倒地 / 救援动作、#4 枪模与拔枪动画、#5 开镜倍率、#6 界面与特效（含 #66 高分辨率下像素字发糊）、#7 新手引导、#8 参考「土豆史诗」的枪感、#9 双击 Shift 滑铲、#10 背包胸挂界面。这些都要截图给服主看。
- **剩余审查发现**：P2 #37–#44、P3 #45–#46，以及整合时发现的 #60–#64。
- 计划中但未开始（等服主确认）：商人剧情任务线、基础反作弊。

### 服主定下的规则（改代码时不要违背）

- **匹配**：每张图、每档难度一个匹配池，同一时段排队的小队进同一局，互为敌人；排位只决定加不加排位分，不影响匹配；开局后名单固定，**不允许中途加入**。一局小队数 = `min(match.max-squads, 地图出生点组数)`。
- **对局中关服**（正常关服和崩溃都一样）：**整局作废**，局内的人恢复出发前的配装和安全箱，退入场费和保险费，发一封系统补偿邮件（块币，可配置）；被作废的人不再走保险理赔，已阵亡 / 已撤离的人照常结算（#67）。
- **爆率**：**不要靠压低产出、封顶获取来控制经济**。箱子照常纯随机出货（顶级物品也有极低的基础概率），另外每个玩家有个人运气：按品质和大奖物品的保底计数（例如 1000 场没出过，第 1001 场必出）、按行为分（赚损比、合作分、KD、出红率）累积的额度池（「工资」，够了就发）、短时间大额亏损后对本人生效的高爆率补偿局；发了没带出去，下把从额度里继续补（#80）。经济靠入场费、收购价等消耗来平衡。
- **刀不计入战备值**（#81）；刀要有检视动作（#82）。
- **分工**：功能测试用假人自己测完再合并，服主只看外观（发截图）；合并由 Claude 做。

### 只有真人能确认的（bot 看不到）

- 画面与手感：持枪姿势、换弹动画、开镜、手电和爆闪、震撼弹白屏（截图已发：`~/RemoteShare/shots/flashbang-whiteout.png`）。
- 持盾兵是否正确挡住正面子弹；敌对干员小队在箱子之间搜刮的表现。

## 构建与部署

```bash
./gradlew jar                 # build/libs/Craftov-1.0.0.jar（JDK 25 工具链，产出 Java 21 字节码）
./gradlew deployLocal         # 构建并复制到 server/run/plugins/Craftov.jar
python3 tools/art/build.py    # 重新生成贴图、模型、音效、BetterHud 布局
```

- 开发在远程服务器上（这台机器的 `java` 就是 25，`./gradlew` 直接能用），真人测试服在用户的 Mac 上，见下面「测试服运维」。
- 没有单元测试目录；验证靠编译 + 部署 + `tools/bot/` 里的 mineflayer 脚本（`node test.js <名字> [场景]`，以及 fire / meds / revive / squad / doors / econ 等专项脚本）。bot 只能连这台机器上的本地测试服（Mac 的 25565 从这边连不到），用离线模式连 `127.0.0.1`，跑之前要临时加白名单，名字用随机的，否则会重连进旧对局。
- 画面相关的改动（枪模、开镜、动作、界面、HUD、特效）用 `tools/shots/shots.py` 在这台机器上跑真客户端截图，见下面「真客户端截图」。
- `tools/art/build.py` 的音效编码是可复现的（同样的采样总得到同样的 .ogg，按内容哈希缓存在 `tools/art/.cache/ogg/`），没改过的音效不会出现在 diff 里，不用再还原 sounds 目录。贴图和模型 JSON 在别的 Pillow / numpy 版本下可能字节不同：没改它们时，提交前用 `git checkout --` 还原这些无关的生成文件。枪声录音要先由 `tools/art/fetch_audio.py` 下载（build.py 会自动调用），需要 `soundfile` 和 `7z`。
- 生成的 BetterHud yml 和资源包文件不要手改，改 `tools/art/` 里的生成器，否则下次构建会被覆盖。

## 测试服运维

真人测试在用户的 Mac 上：测试服是 Mac 上 `~/Documents/projects/plugins/EscapeFromCraftov/server/run`（被 gitignore，由 `server/setup.sh` 生成）。这台服务器通过 mac-bridge（`ssh mac`，只能运行 Mac 上的门卫脚本）和门卫扩展 `tools/mac/craftov` 操作它，命令一律走 `tools/mac/mac`：

- **部署**：`tools/mac/mac deploy` = 把 `main` 的提交同步到 Mac 项目（git bundle 走 ssh，Mac 不联网）+ 在这边 `./gradlew jar` 后推过去 + 重启并等到 `Done (`。只换插件：`tools/mac/mac jar` 再 `tools/mac/mac restart`。jar 用改名的方式替换，运行中的服务器不受影响。
- **开关服 / 状态**：`tools/mac/mac start|stop|restart|status`。扩展会处理 Mac 上的 `run-forever.sh` 重启循环（5 秒后自动拉起）和用户手动开的 `screen -S craftov`：关服先结束循环再 RCON `stop`，等 `paper.jar` 退出。不要用 `ssh mac` 之外的方式去动 Mac 上的进程。
- **控制台命令**：`tools/mac/mac rcon <命令>`，命令原样传过去（JSON 引号保留）。
- **日志**：`tools/mac/mac log [行数]`（`logs/latest.log`）；开服失败时 Paper 还没打开日志前的报错（坏 jar、java 起不来）在 `tools/mac/mac consolelog [行数]`；客户端日志 `tools/mac/mac clientlog [行数]`，排查资源包下载失败（`download_failed`）时看。
- **Mac 测试服的 `config.yml` 是旧版生成的**：缺的键会回落到 jar 默认值，但已有的键不会更新（例如 `match.wait-seconds` 还是 15，新默认是 30）。改动默认值时要提醒服主或在 Mac 上手动改。
- **白名单**：`online-mode=false`，离线 UUID 由名字的**确切大小写**算出，`whitelist add` 必须用玩家真实的大小写（看日志里的 `UUID of player Xxx`）。用户的名字是 `Snowball_233`。
- **资源包地址**：`plugins/CraftEngine/config.yml` 的 `self-host.ip`，资源包托管在 MC 同一端口 25565。改用 `tools/mac/mac packip <IP>`（服务器运行中会自动 `ce reload all`）。平时用 Mac 的局域网 IP，`start.sh` 每次开服会自动刷新私有网段的地址；用户要让外网玩家进来时会告诉你公网 IP（"换ip x.x.x.x"），公网 IP 不会被自动覆盖，外网要能连还需要用户把 25565 映射到 Mac。Mac 当前的公网 IP 可以从这台机器 `who` 里用户 ssh 进来的地址看到。
- **服务器上的 `plugins/Craftov/items.yml`、`loot.yml` 只在缺失时才从 jar 复制**。改了物品后先 `tools/mac/mac yml diff items.yml` 确认服务器那份没有手动改动，再 `tools/mac/mac yml copy items.yml`（自动备份旧的），然后重启。`config.yml` 缺的键会回落到 jar 里的默认值。
- **同步被拒绝**：Mac 项目有未提交改动、或有这边没有的提交时，`sync` 会拒绝而不是覆盖。`tools/mac/mac pull-mac` 把 Mac 的本地分支取到这边的 `refs/mac/*` 查看；确认保住之后用 `tools/mac/mac sync --park <名字>` 把那个分支改名保留，再切到 `main`。
- **扩展本身**：Mac 上的 `~/.local/libexec/remote-gate/craftov` 会在每次 `sync`（包括 `deploy`）之后自动换成同步过去的 `tools/mac/craftov`，所以改扩展只要提交再 `deploy`；没提交时用 `tools/mac/mac selfupdate` 直接推工作区里的版本。替换前新版本必须在 Mac 上跑通 `selfupdate --check`，否则保留旧的，所以**新版本必须保留 `selfupdate` 指令**，不然以后就只能请用户手动装（`ssh mac put craftov < tools/mac/craftov`，用户在 Mac 终端运行 `install -m 755 ~/RemoteShare/craftov ~/.local/libexec/remote-gate/craftov`）。门卫 `~/.local/bin/remote-gate` 不会自动更新，源码在用户的 `SnowballXueQiu/mac-bridge` 仓库，改它要用户手动装。
- **本机无头测试服**（给 bot 用）：在这台机器上 `server/setup.sh --local` 生成 `server/run/`，`server/run/start.sh` 开服；控制台命令用 `cd tools/bot && node -e "require('./rcon')('命令').then(console.log)"`（需要在 `server.properties` 里打开 RCON，密码和 `tools/bot/rcon.js` 一致）。

## 真客户端截图（`tools/shots/`）

mineflayer 不渲染画面。要看「长什么样」时，`tools/shots/shots.py` 在这台机器上跑真正的 1.21.11 原版客户端：Xvfb 虚拟显示 + Mesa llvmpipe 软件渲染，离线账号 `ShotCam`（客户端在线期间临时 op，服务器开着白名单时也临时加进去，关客户端时收回；被踢、被封或进不去时直接报出服务器给的原因），Quick Play 直接进本地测试服；`servers.dat` 里给这个地址设了 `acceptTextures`，资源包不弹提示直接加载（客户端日志里最后一次 `Reloading ResourceManager` 带着 `server/...` 并且拼好了贴图集才算进服完成；资源包下载失败或重载失败被客户端回滚成原版时直接报错，不会拿原版贴图去截图）。

```bash
tools/shots/shots.py run                         # 默认场景：lobby gun inventory deploy raid，约 100 秒
tools/shots/shots.py run gun:k47 inventory       # 指定场景，冒号后是参数（这里是枪的物品 ID）
tools/shots/shots.py run --build --fresh raid    # 先构建插件并重启测试服；把 ShotCam 重置成新玩家（新手物资）
tools/shots/shots.py up | down | status          # 服务器和客户端常驻，反复调场景时省掉每次约 30 秒的启动
tools/shots/shots.py send m4c-ads raid-hud       # 发到服主 Mac 的 ~/RemoteShare/shots/ 并打开；run --send 发本次全部
```

- **一条 `run` 走完全程**：测试服没开就开（`--server-dir` 默认 `server/run`，不存在时先 `setup`：`server/setup.sh --local` + 只监听 127.0.0.1 + 打开 RCON + 从 jar 取完整的 `config.yml` 再改 `test.enabled: true` 和 `match.wait-seconds: 5`）→ 开虚拟显示和客户端进服 → 跑场景 → 截图存到 `tools/shots/out/<名字>.png` → 只关掉这次自己开的东西（`--keep` 都留着）。端口用 `--port` 或 `SHOTS_PORT`（默认 25565，RCON 默认是端口 +10）。同一个检出一次只跑一个。
- **端口上的服务器必须就是 `--server-dir` 里的那个**（按监听进程的工作目录判断），否则直接拒绝：在 `.claude/worktrees/` 里一定要给自己的 `--port`（例如 `--port 25951`），不然会撞上主检出的测试服。已有的服务器目录 `server.properties` 里端口 / RCON 对不上时开服前就报错（`shots.py setup --port N` 会写好），开服超时会把它关掉而不是留在后台。`--build` 只会重启 shots 自己开的服务器，别人开的会被拒绝；局内场景撤离失败时只结束 ShotCam 独自一人的那局，否则留着并报错。
- 第一次运行在 `tools/shots/.cache/` 建 venv（`minecraft-launcher-lib`，BSD-2-Clause）并下载客户端（约 550 MB，用系统的 JDK 25 启动）。`.cache/` 和 `out/` 都被 gitignore；客户端日志在 `tools/shots/.cache/game/logs/latest.log`。
- **场景**是 `tools/shots/scenes/<名字>.py` 里的 `run(s)`，几行就能写：`s.rcon(...)`、`s.cmd('cv deploy')`（在客户端聊天栏打指令）、`s.hold('m4c')`（没有就发一把并选中）、`s.key('F5')`、`with s.mouse(3):`（按住右键）、`s.park_mouse()`（界面打开后把鼠标挪到角落，免得出现物品提示）、`s.clear_chat()`、`s.extract('proving', raids)`、`s.shot('名字')`；要画面里有另一个玩家时用 `with s.puppet() as mate:`（`tools/bot/puppet.js` 的 mineflayer 假人，`mate.hold('m4c')`、`mate.tp(...)`、`mate.orbit(角度)` 让镜头绕着它看）。内置场景：大厅；手持枪腰射 / 开镜 / 第三人称背面和正面；背包；部署菜单；试验场局内 HUD + 开火 + 换弹（最后从常驻撤离点撤离，保住装备，下次不会重连进旧对局）。另有 `others`：别的玩家眼里的双手持枪姿势（自己的第三人称看不到装备包改写），需要 `tools/bot/node_modules`，所以不在默认场景里。
- 截完**自己先看一遍**（Read 能直接看 PNG），画面确实是场景说的样子再发给服主。

## 协作方式

- **分工：功能测试由假人自测，服主只看外观。** 伤害、经济、匹配、流程这些能用 `tools/bot/` 验证的，自己跑完再交付，不要请服主测；只有画面和手感需要服主，而且最好直接给截图（`tools/shots/shots.py run <场景> --send`），而不是让服主自己进游戏去找。
- **服主只负责策划和最终测试，合并由 Claude 操作。** 问题都记成 GitHub issue（`Inf-Network/EscapeFromCraftov`，标签：`P0-严重`…`P3-低`、`用户反馈` / `审查发现`、模块）。每个 issue 一个分支和一个 PR，不直接往 `main` 推。
- PR 测试（编译 + `tools/bot/` 无头场景）和独立审查都通过后，由 Claude 用**普通合并提交**合进 `main`（不要 squash：后面叠着的分支要能干净接上）。PR 的目标分支是别的分支时，合并前先改成 `main`；**不要在还有 PR 以它为目标分支时删除这个分支**，GitHub 会直接关掉那些 PR。
- PR 不是以 `main` 为目标时，`Closes #N` 不会生效，合并后要手动关掉对应 issue 并注明 PR 编号。
- 合并后 `tools/mac/mac deploy` 部署到 Mac 测试服（items.yml / loot.yml 有改动时先 `yml diff` 再 `yml copy`），再把「需要进游戏确认」的清单整理给服主，**告诉服主具体测什么**（画面类的附上截图），而不是只说"已修复"。
- 几个修复并行写时会互相重叠（同一个问题修两遍、关服 / 存档 / 保险 / 护航等路径改到同一处）。合并前要按依赖顺序整合成一条线，并专门检查同一件事有没有被执行两次。
- **批量修复的做法**：用 Workflow 按「实现 → 独立审查 → 修复审查意见」处理每个 issue，每个 issue 在 `.claude/worktrees/` 下自己的 worktree 和分支里做（已写进 `.git/info/exclude`），各自用不同端口起自己的测试服（25611、26011… 这类，RCON 为端口 +10），绝不碰主检出的 `server/run` 和 25565。会改到同一处的 issue 放进同一条链依次叠放；几条链之间并行，做完后合进一个整合分支解决冲突，再做一次「有没有执行两次」的全栈审查，然后才合并。
- **会话中断**：Workflow 的运行记录存在当次会话的目录（`~/.claude/projects/<项目>/<会话 id>/subagents/workflows/<运行 id>/journal.jsonl`），换了会话就不能直接 resume。要从旧会话的 journal 里取出已完成的结果，手写一个续跑脚本只跑剩下的步骤；中断的代理可能在 worktree 里留下未提交的改动，续跑前先检查。
- 团队成员也会在 GitHub 提 issue（例如 ImCZFy，是服主的队友）：打上模块和优先级标签，归到对应的大 issue 里一起处理，并在 issue 里回复一句会在哪里处理。

## 提交规范

- 一个提交只做一件事，每个提交都要能单独编译。不要攒一大堆改动一次提交。
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
