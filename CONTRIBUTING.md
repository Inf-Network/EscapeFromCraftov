# 参与贡献

感谢你愿意帮 Craftov 变得更好。下面是这个项目的工作方式，照着做能让改动更快合并。

## 先开 issue

- 所有改动都从 [issue](https://github.com/Inf-Network/EscapeFromCraftov/issues) 开始：bug、玩法需求、手感问题都可以提，用 issue 模板填写即可。
- 想做某个 issue 时先在下面留言，避免两个人做同一件事。
- **刷物品、刷钱、越权、让服务器崩溃的漏洞不要公开提 issue**，按 [SECURITY.md](SECURITY.md) 私下报告。
- issue 标签：优先级 `P0-严重` … `P3-低`；来源 `用户反馈`（服主或玩家的反馈）/ `审查发现`（代码审查中发现）；模块（`匹配`、`射击`、`界面` 等）。

## 开发环境

- JDK 25（Gradle 工具链，产出 Java 21 字节码）、Python 3、Node.js（无头测试用）。
- 构建：`./gradlew jar`，产物在 `build/libs/`。
- 本地测试服：`./server/setup.sh --local` 生成 `server/run/`，再 `server/run/start.sh`；`./gradlew deployLocal` 把插件装进去。
- 贴图、模型、音效、HUD 布局都由 `python3 tools/art/build.py` 生成。**生成出来的资源包文件和 BetterHud 布局不要手改**，改 `tools/art/` 里的生成器。

## 写代码的约定

- 每个系统一个 `XxxService`，按领域分包（`weapon`、`raid`、`combat`、`gui` …），在 `CraftovPlugin.onEnable` 里构造。
- 玩家看得到的文字全部用中文，走 `util/Text`（MiniMessage）；代码注释和 Javadoc 用英文，写「为什么」。
- 数值放 `config.yml`（代码里带同样的默认值），物品放 `items.yml`，掉落放 `loot.yml`；新增配置要同时写进 `src/main/resources/config.yml`。
- 物品的构造方式变了，就把 `ItemFactory.VERSION` 加一，玩家身上的旧物品会自动重建。
- 玩家能感知到的改动要同步更新 `README.md`。

## 提交与分支

- 一个 issue 一个分支、一个 PR，不直接推 `main`。
- 一个提交只做一件事，每个提交都能单独编译。
- 提交信息用英文 Conventional Commits：`feat:` / `fix:` / `tune:` / `docs:` / `refactor:` / `chore:`，主题行小写、描述玩家能感知到的变化（例如 `fix: switching guns no longer skips a bolt cycle`），正文用几句完整的话说明做了什么、数值是多少。提交信息里不要加 `Co-Authored-By` 之类的署名行。
- 建议给提交签名（GPG 或 SSH 签名，GitHub 上显示 Verified）。

## 测试

- 能自动验证的都自己验证：编译通过，再用 `tools/bot/` 里的无头客户端脚本（mineflayer，`npm install` 后运行，脚本开头写了需要的测试服配置）跑相关场景。新功能最好附一个能重复运行的场景脚本。
- 改到画面的（枪模、动作、开镜、界面、HUD、特效），用 `tools/shots/shots.py` 在本地跑真实客户端截图，把截图贴进 PR。
- PR 按模板写清：改了什么、怎么测的、合并后测试服要做什么、哪些需要真人看。

## 许可

本项目以 [GNU AGPL-3.0](LICENSE) 授权。提交贡献即表示你同意你的贡献以同样的许可发布。不要提交来源不明或许可不兼容的素材（贴图、模型、音效）；用第三方素材时在 README 的「许可」一节注明来源和许可。

## 行为准则

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。
