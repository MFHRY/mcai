# mcAI

在 Minecraft 里和 AI 聊天，还能按键让它**看你屏幕**。

一个面向 **Minecraft 1.21.1 / Fabric** 的客户端模组：在聊天栏用 `!ai` 提问、按 `H` 截取当前画面交给多模态模型识别，并附带思考过程显示、耗时统计和 Token 用量记录。

> **1.20 起，不花钱也能用**：把接口地址指向本机模型（Ollama / LM Studio）就不需要 API Key，完全离线。
> 另有 `/ai craft` 这类**零 token** 的本地功能——它读服务器已经同步给你的配方表，不联网、不花钱。

> 核心链路（`!ai` 问答、`H` 截图识别）是**纯客户端**的，因此**在任意服务器上都能用**，包括原版服务器。
> 只有「切换器物品」的发放依赖服务端（见下方[兼容性说明](#服务器兼容性)）。

---

## 1.20 新功能

| 功能 | 触发方式 | 说明 |
| --- | --- | --- |
| **本地模型免 Key** | 改 `api_url` | 指向 `http://localhost:11434/v1`（Ollama）或 LM Studio，**离线免费** |
| **花费估算** | `/ai cost` | 按 DeepSeek 官方价格表估算人民币，**自动区分高峰/空闲**两档单价 |
| **每日硬预算** | `/ai budget <元>` | 达到上限直接拒绝新请求，不会月底才发现超支 |
| **多 Key 降级** | 配置 `backup_api_keys` | 遇到 401 / 402 / 429 自动切到下一个 Key |
| **多轮上下文** | `/ai history <轮数>` | **只存内存**，不写盘；`/ai clear` 立即忘记 |
| **人格预设** | `/ai persona <id>` | `builder` / `redstone` / `survival` / `english`，改变 AI 的回答口吻与侧重 |
| **流式输出** | `/ai streaming on` | 回答边生成边显示在头顶 Action Bar |
| **建筑/红石读图** | 按 `G` | 同一个截图链路，换成"分析原理 + 给改进建议"的提示词 |
| **无障碍朗读** | `/ai describe` | 把屏幕上的界面、方块、生物、危险念出来 |
| **自动裁剪界面** | 开着箱子按 `H` | 只发容器那一块，**图片 token 降到约四分之一**，格子也更容易看清 |
| **死亡复盘** | `/ai death on` | 截取死亡界面，告诉你被什么杀了、下次该怎么做（**默认关闭**） |
| **Discord 播报** | `/ai discord <网址>` | 死亡时推送到 webhook（**不调用模型，零 token**） |
| **零 token 配方** | `/ai craft` | 列出你背包材料现在能做出的东西 |
| **配方反查** | `/ai craft <物品名>` | 例如 `/ai craft 钻石镐`，直接查那样东西怎么做 |
| **本地回答配方** | `!ai 怎么做钻石镐` | 命中时**完全不发请求**，本地直接答 |
| **环境顾问** | `/ai where [问题]` | 把坐标、群系、光照、维度、生命值作为上下文发出去 |
| **告示牌/成书翻译** | `/ai read` | **发文本而不是图片**，比 OCR 便宜得多、也准得多 |
| **用量曲线** | `/ai chart` | 最近 7 天的 token 用量柱状图 |
| **背包任务** | `/ai task` | 根据背包里真实有的东西生成 3 个小任务 |

> **1.21 修好了 1.20 的四个 bug**（`/ai config` 打不开、流式看不见、流式丢思维链、
> 新功能只能手改 `mcai.json`）。完整清单见 [CHANGELOG.md](CHANGELOG.md)。
> **1.21 起所有开关都能在游戏里设置**，不必再手改配置文件。

---

## 功能特性

| 功能 | 触发方式 | 说明 |
| --- | --- | --- |
| AI 聊天问答 | 聊天栏输入 `!ai <问题>` | 8 秒冷却，答案直接显示在聊天栏 |
| 截图视觉识别 | 按 `H` | 截取当前画面，本地压缩后交给多模态模型识别，3 秒冷却 |
| 切换模型 | 右键「模型切换器」 | 在 `available_models` 里循环切换 |
| 切换模式 | 右键「模式切换器」 | 在 `available_modes` 里循环切换 |
| 思考过程 | 自动 | 显示推理模型的思维链（`reasoning_content`），长度可配置 |
| 思考动画 | 自动 | 请求期间聊天栏提示 + 头顶 Action Bar 旋转的 `-\|/` |
| 耗时统计 | 自动 | 回答末尾附带 `(思考：3.2秒)` |
| Token 统计 | `/ai token` | 今日消耗 + 带时间戳的历史明细，并写入 CSV |
| 进服功能清单 | 自动 / `/ai help` | 进入世界时在聊天栏列出所有触发方式 |

### 亮点

- **中英双语提示**：跟随游戏语言自动切换，不用装两个版本。中文环境显示中文（`401 密钥无效` / `402 余额不足` / `429 请求过于频繁`…），英文环境显示英文（`Invalid API key` / `Insufficient balance` / `Too many requests`…）
- **连 AI 的回答语言也跟着切换**：系统提示词按语言生效，英文环境下 AI 直接用英文回答
- **跨天自动清零** Token 计数与当日花费（依据 `token_date` 字段）
- **所有网络请求和文件读写都是异步的**，不会卡住游戏主线程
- **配置读写有防内存泄漏处理**：截图产生的 `NativeImage`（堆外内存）和 `BufferedImage` 都会被显式释放
- 会**主动拦截不支持读图的模型**，避免白烧 token
- **花费是估算，不是账单**：价格表内置 DeepSeek 官方单价，未收录的模型显示"无法估算"而不是瞎猜

---

## 多语言 / Localization

同一个 jar 同时内置中文和英文，**跟随游戏的语言设置自动切换**，不需要下载单独的"英文版"。

已本地化的内容：

| 内容 | 键前缀 | 例子（中文 → 英文） |
| --- | --- | --- |
| 物品名与悬浮提示 | `item.mcai.*` / `tooltip.mcai.*` | 模型切换器 → AI Model Switcher |
| 快捷键与分类 | `key.mcai.*` / `category.mcai` | 截图识别（视觉） → Screenshot Recognition (Vision) |
| 聊天栏全部提示 | `mcai.chat.*` / `mcai.error.*` | 请不要刷屏 → Please slow down |
| API 错误码解释 | `mcai.http.*` | 402 余额不足 → Insufficient balance |
| 思考过程与耗时 | `mcai.reasoning.*` / `mcai.thinking.*` | `(思考：3.2秒)` → `(3.2s)` |
| 厂商名与能力等级 | `mcai.vendor.*` / `mcai.tier.*` | 深度求索 → DeepSeek |
| 游戏内设置窗口 | `mcai.screen.*` / `mcai.help.*` | API 是什么？ → What is an API? |
| `/ai` 全部回显 | `mcai.cmd.*` | 今日消耗 → Today |
| 进服功能清单 | `mcai.guide.*` | 功能与指令一览 → Features and commands |
| 发给 AI 的系统提示词 | `mcai.prompt.system` | 决定 AI 用中文还是英文回答 |

想加别的语言（比如 `ja_jp` / `ru_ru`），只要在 `src/main/resources/assets/mcai/lang/` 下
新建 `xx_xx.json`，把那 247 个键翻译一遍即可，**不用改任何 Java 代码**。

> 只有游戏内文案会翻译；`config/mcai.json` 里存的值和 `config/mcai-usage.csv` 里的记录
> 始终保持 ASCII 且与语言无关，切换语言不会破坏已有配置和历史统计。

---

## 快速开始

### 1. 安装

需要 **Minecraft 1.21.1** + **Fabric Loader** + **[Fabric API](https://modrinth.com/mod/fabric-api)**。

把 `mcai-x.y.z.jar` 和 Fabric API 一起放进 `.minecraft/mods/`：

```
.minecraft/mods/
├── fabric-api-0.116.17+1.21.1.jar
└── mcai-1.21.jar
```

### 2. 填写 API Key

**最快的方式：进游戏里用 `/ai config`** —— 会打开一个设置窗口，可以填 API Key、接口地址、
模型，还能直接开关死亡复盘 / 流式输出 / 本地配方，并设置每日预算和上下文轮数。

也可以手改配置文件。第一次启动游戏后会自动生成：

```
.minecraft/config/mcai.json
```

默认配置指向 **DeepSeek 官方 API**：

```json
{
  "api_key": "",
  "api_url": "https://api.deepseek.com/v1",
  "model": "deepseek-flash"
}
```

> 任何 **OpenAI 兼容**的接口都能用：把 `api_url` 改成你的服务地址（程序会自动补 `/chat/completions`），
> `model` 填对应的模型 ID。

#### 不想花钱？用本机模型

把 `api_url` 指向本机跑的 OpenAI 兼容服务，**`api_key` 可以留空**：

```json
{
  "api_key": "",
  "api_url": "http://localhost:11434/v1",
  "model": "qwen2.5:7b"
}
```

- **Ollama**：装好后 `ollama pull qwen2.5:7b`，地址填 `http://localhost:11434/v1`
- **LM Studio**：启动本地服务器，地址填它显示的地址（通常是 `http://localhost:1234/v1`）

> 程序会识别 `localhost` / `127.0.0.1` / `[::1]`，这些地址**不再要求填 Key**，
> 也不会因为没有 Key 而报错。局域网上的其他机器（如 `192.168.x.x`）**不算本地**——
> 它同样可能把你的截图发到别处，所以仍需填写 Key。
>
> 本地小模型的看图能力远不如云端大模型，别期待它准确读出画面里的细节文字。

### 3. 开始使用

进入世界后聊天栏会列出所有功能，然后：

```
!ai 怎么合成钻石镐？
```

或直接按 `H` 让它看你的屏幕，按 `G` 分析你正在看的建筑/红石电路。

---

## 命令一览

| 命令 | 作用 |
| --- | --- |
| `/ai config` | **打开游戏内设置窗口**：API Key、接口地址、模型，以及 1.20 的三个开关和预算/上下文输入框 |
| `/ai status` | 查看当前模型、模式、截图分辨率、今日消耗与花费、人格、上下文轮数（API Key 会打码） |
| `/ai token` | 今日 Token 消耗 + 最近 8 次调用明细（时间 / token 数 / 来源 / 模型） |
| `/ai cost` | 今日花费估算、当前单价（高峰/空闲）、预算剩余 |
| `/ai chart` | 最近 7 天 token 用量柱状图 |
| `/ai resolution <360p\|720p\|1080p\|original>` | 调整截图清晰度，**支持 Tab 补全**；也接受 `480p` 这类自定义高度，以及旧写法 `原始` |
| `/ai where [问题]` | 带上坐标、群系、光照、维度、生命值去问 AI |
| `/ai read` | 翻译准星指向的告示牌，或手中的成书（**发文本，不发图**） |
| `/ai describe` | 朗读屏幕内容（无障碍） |
| `/ai craft` | 列出背包材料现在能做出的东西（**零 token**） |
| `/ai craft <物品名>` | 按名字反查配方，例如 `/ai craft 钻石镐` |
| `/ai task` | 根据背包生成任务；`/ai task show` 查看、`/ai task clear` 清除 |
| `/ai persona <id>` | 切换人格：`default` / `builder` / `redstone` / `survival` / `english` |
| `/ai death on\|off` | 死亡复盘开关（默认关） |
| `/ai streaming on\|off` | 流式输出开关 |
| `/ai recipe on\|off` | 本地配方回答开关 |
| `/ai budget <元>` | 每日预算（元），`0` 表示不限制 |
| `/ai history <轮数>` | 保留几轮对话上下文，`0` 关闭 |
| `/ai discord <网址\|clear>` | 设置/清除 Discord 播报 webhook |
| `/ai clear` | 清空内存中的对话上下文 |
| `/ai help` | 重新显示功能清单 |

### 截图分辨率怎么选

- `360p` / `720p` / `1080p`：**只缩小、不放大**。窗口比目标还小时保持原样，因为放大只会浪费 token 而不增加信息量。
- `原始` / `original`：完全不缩放。命令行参数请用 `original`（`原始` 仍作为兼容写法保留）。
- 分辨率越低，请求越快越省 token，但字太小的画面可能识别不准。

> 配置文件里存的值**始终是 ASCII**（`original` 而不是 `原始`），这样切换游戏语言不会
> 影响已有配置，国际玩家的配置文件也不会出现编码问题。

> 实测：一张 1280×720 的图压到 **360p**，JPEG 约 **14 KB**，模型依然能准确读出画面里的文字。

---

## 配置文件

`config/mcai.json`

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `api_key` | `""` | 你的 API Key。**指向本机模型时可以留空** |
| `api_url` | `https://api.deepseek.com/v1` | 接口地址，自动补 `/chat/completions`。填 `http://localhost:11434/v1` 即可用 Ollama |
| `model` | `deepseek-flash` | 当前使用的模型 |
| `available_models` | `["deepseek-flash", "deepseek-v4-pro"]` | 「模型切换器」右键循环的列表 |
| `mode` | `chat` | 当前模式 |
| `available_modes` | `["chat", "vision"]` | 「模式切换器」右键循环的列表 |
| `vision_resolution` | `720p` | `H` 截图的目标分辨率，取值 `360p` / `720p` / `1080p` / `original` |
| `show_reasoning` | `true` | 是否在聊天栏显示思维链 |
| `reasoning_max_chars` | `500` | 思维链最多显示多少字；**设为 `0` 显示完整思考**（可能刷屏，实测单个问题可达 4000~6600 字）。注意这只影响**显示**，不减少花费 |
| `daily_tokens` | `0` | 今日消耗，程序自动维护 |
| `daily_cost_yuan` | `0.0` | 今日花费估算（元），程序自动维护 |
| `token_date` | 当天日期 | 用于跨天清零，程序自动维护 |
| `daily_budget_yuan` | `0.0` | **每日预算（元）**，`0` 表示不限制；达到上限后拒绝新请求 |
| `backup_api_keys` | `[]` | 备用 API Key，主 Key 遇到 401/402/429 时自动切换 |
| `history_turns` | `4` | 多轮上下文保留几轮，`0` 关闭。**只存内存，不写盘** |
| `streaming` | `true` | 是否使用流式输出（回答实时显示在 Action Bar） |
| `recipe_cache` | `true` | 是否让"怎么做 X"在本地直接用配方表回答（零 token） |
| `persona` | `default` | 人格预设 |
| `death_recap` | `false` | **死亡复盘**，默认关闭。会自动把死亡画面发给模型，介意隐私就别开 |
| `discord_webhook` | `""` | Discord 播报地址，留空即关闭 |

> 以上每一项目前都能在游戏里设置（`/ai config` 或对应的 `/ai` 指令），
> 手改 `mcai.json` 也可以 —— 两种方式等价，改完即时生效。

### Token 使用明细

除了 `daily_tokens` 的今日总数，每次调用还会追加一条明细到：

```
config/mcai-usage.csv
```

```csv
time,model,source,tokens,cost
2026-10-08 21:26:01,deepseek-flash,chat,3985
2026-10-08 21:26:01,deepseek-flash,vision,666
```

标准 CSV 格式，**可以直接用 Excel 打开**做统计分析。

---

## 模型兼容性

| 模型 | 图片识别 | 备注 |
| --- | --- | --- |
| `deepseek-flash` | ✅ | 实测能准确读出画面文字，推荐 |
| `deepseek-v4-pro` | ❌ | **不支持图片输入** |

> ⚠️ 关于 `deepseek-v4-pro`：它**不会返回错误**，而是礼貌地回一句"我无法读取这张图片的内容" ——
> 既浪费 token 又让人困惑。所以本模组在发起截图请求前会先检查模型的视觉能力，
> 不支持时直接给出中文提示并中止，不会发出请求。
>
> 这个能力表维护在 [`ModelCatalog.java`](src/main/java/com/example/mcai/util/ModelCatalog.java)，
> 想支持新模型只要加一行 `put(...)`。

---

## 服务器兼容性

| 功能 | 单人 | 多人（服务端未装） | 多人（服务端已装） |
| --- | :---: | :---: | :---: |
| `!ai` 聊天问答 | ✅ | ✅ | ✅ |
| `H` 截图识别 | ✅ | ✅ | ✅ |
| `/ai` 指令与统计 | ✅ | ✅ | ✅ |
| 进服自动发放切换器 | ✅ | ❌ | ✅ |

聊天和截图是**纯客户端**行为（`!ai` 消息会被客户端拦截，不会发送到服务器），所以在别人的服务器上也能正常用。
如果服务端没装本模组，就拿不到两个切换器物品，此时改模型/模式需要直接编辑 `mcai.json`。

---

## 构建

**必须使用 JDK 21。**

```bash
./gradlew clean build
```

产物在 `build/libs/mcai-1.21.jar`。

### ⚠️ 关于 JDK 版本

Minecraft 1.21.1、Fabric Loom 1.7.x、Gradle 8.8 **都要求 JDK 21**。用 JDK 25 会失败：

```
Unsupported class file major version 69
```

如果你的 `java` 不是 JDK 21，请在**本机级**（不要提交进仓库）指定：

```properties
# %USERPROFILE%\.gradle\gradle.properties   (Windows)
# ~/.gradle/gradle.properties               (Linux / macOS)
org.gradle.java.home=C:/path/to/your/jdk-21
```

> 本项目刻意**没有**把 `org.gradle.java.home` 写进仓库的 `gradle.properties`：
> 那是绝对路径，既会泄露机器信息，也会让所有克隆者构建失败。

### 在 IDE 里运行

```
./gradlew runClient
```

---

## 实现札记

开发过程中踩到的一批 **1.21.1 特有的 API 陷阱**，记录下来供后来者参考 —— 这些用新版教程的写法**全都编译不过**：

| 常见写法 | 1.21.1 的真实情况 |
| --- | --- |
| `KeyMapping` / `KeyMapping.Category` | 类名是 **`KeyBinding`**，且**没有 `Category`**，分类参数是普通 `String` |
| `ScreenshotRecorder.takeScreenshot(renderer, callback)` | 是**同步返回 `NativeImage`** 的 `takeScreenshot(Framebuffer)` |
| 截图后手动 `mirrorVertically()` | 它**内部已经翻转过**了，再翻一次图会上下颠倒 |
| `UseItemCallback` 返回 `ActionResult` | 返回 **`TypedActionResult<ItemStack>`**（1.21.1 的 `ActionResult` 只是普通 enum） |
| `PacketCodec.of(ValueEncoder, …)` | 第一个参数类型是 **`ValueFirstEncoder`** |
| `StringArgumentType.word()` 接收任意文本 | **只接受 ASCII**，中文参数（如 `原始`）会解析失败，必须用 literal 节点。本模组因此把标准值定成 ASCII 的 `original`，中文只作为额外的 literal 别名 |
| `I18n.translate()` 到处用 | 它在 `net.minecraft.client.*` 里，装到**专用服务端**会 `NoClassDefFoundError`。改用 common 包里的 `Text.translatable(...).getString()`，两端都安全 |
| `NativeImage.getColor()` 通道顺序 | 返回 **ARGB**（名字里的 `RGBA` 有歧义，已用真实类实测确认） |
| `NativeImage` 不用管 | 占**堆外内存**，不 `close()` 会真泄漏 |

其他值得一提的设计：

- **Gson 不支持 `java.time.LocalDate`**（MC 自带 2.10.1）。直接把 `LocalDate` 字段丢给 Gson 会抛
  `JsonIOException`。本模组注册了自定义 `TypeAdapter`，并且**把它做成嵌套类就地实例化** ——
  因为 `INSTANCE` 静态字段先于它初始化，写成静态字段会在构造时拿到 `null`。
- **思考动画用引用计数 + 幂等的 `finish()`**。任何异常路径漏掉"停止动画"都会让 Action Bar 永远转下去，
  所以 `finish()` 用 CAS 保证只生效一次，调用点还在 `finally` 里兜了一层。
- **网络包传"绝对值"而不是"切换指令"**。单人游戏里客户端和整合服务端共用同一个 `ConfigManager` 单例，
  传指令会被执行两次导致一次右键连跳两个模型。

---

## 支持这个项目

mcAI 完全免费，以 **CC0** 协议开源 —— 可自由使用、修改、再分发（含商用），无需署名。
项目不设付费功能、没有广告，也不会因为你没赞助而限制任何东西。

如果它确实帮到了你，欢迎：

- ⭐ **给仓库点个 Star** —— 这是最实际的支持，能帮更多人看到它
- 🐛 在 [Issues](https://github.com/MFHRY/mcai/issues) 里反馈问题或建议
- 💰 **请作者喝杯咖啡** —— [爱发电主页](https://afdian.com/a/1145141919810aaac)

| 支付宝 | 微信支付 |
| :---: | :---: |
| <img src="docs/donate/alipay.jpg" width="230" alt="支付宝赞赏码"> | <img src="docs/donate/wechat.jpg" width="230" alt="微信赞赏码"> |

> 赞助完全自愿。**不赞助也能使用全部功能，而且永远如此。**

感谢每一位使用者 ❤️

---

## 许可证

[CC0 1.0 Universal](LICENSE) — 公共领域贡献，可自由使用、修改、再分发。

基于 [Fabric Example Mod](https://github.com/FabricMC/fabric-example-mod) 模板创建。
