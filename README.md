# mcAI

在 Minecraft 里和 AI 聊天，还能按键让它**看你屏幕**。

一个面向 **Minecraft 1.21.1 / Fabric** 的客户端模组：在聊天栏用 `!ai` 提问、按 `H` 截取当前画面交给多模态模型识别，并附带思考过程显示、耗时统计和 Token 用量记录。

> 核心链路（`!ai` 问答、`H` 截图识别）是**纯客户端**的，因此**在任意服务器上都能用**，包括原版服务器。
> 只有「切换器物品」的发放依赖服务端（见下方[兼容性说明](#服务器兼容性)）。

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

- **全中文提示**，包括 API 错误码（`401 密钥无效` / `402 余额不足` / `429 请求过于频繁`…）
- **跨天自动清零** Token 计数（依据 `token_date` 字段）
- **所有网络请求和文件读写都是异步的**，不会卡住游戏主线程
- **配置读写有防内存泄漏处理**：截图产生的 `NativeImage`（堆外内存）和 `BufferedImage` 都会被显式释放
- 会**主动拦截不支持读图的模型**，避免白烧 token

---

## 快速开始

### 1. 安装

需要 **Minecraft 1.21.1** + **Fabric Loader** + **[Fabric API](https://modrinth.com/mod/fabric-api)**。

把 `mcai-x.y.z.jar` 和 Fabric API 一起放进 `.minecraft/mods/`：

```
.minecraft/mods/
├── fabric-api-0.116.17+1.21.1.jar
└── mcai-1.0.0.jar
```

### 2. 填写 API Key

第一次启动游戏后会自动生成配置文件：

```
.minecraft/config/mcai.json
```

把 `api_key` 填上即可。默认配置指向 **DeepSeek 官方 API**：

```json
{
  "api_key": "",
  "api_url": "https://api.deepseek.com/v1",
  "model": "deepseek-flash"
}
```

> 任何 **OpenAI 兼容**的接口都能用：把 `api_url` 改成你的服务地址（程序会自动补 `/chat/completions`），
> `model` 填对应的模型 ID。

### 3. 开始使用

进入世界后聊天栏会列出所有功能，然后：

```
!ai 怎么合成钻石镐？
```

或直接按 `H` 让它看你的屏幕。

---

## 命令一览

| 命令 | 作用 |
| --- | --- |
| `/ai status` | 查看当前模型、模式、截图分辨率、今日消耗、思考显示设置（API Key 会打码） |
| `/ai token` | 今日 Token 消耗 + 最近 8 次调用明细（时间 / token 数 / 来源 / 模型） |
| `/ai resolution <360p\|720p\|1080p\|原始>` | 调整截图清晰度，**支持 Tab 补全**；也接受 `480p` 这类自定义高度 |
| `/ai help` | 重新显示功能清单 |

### 截图分辨率怎么选

- `360p` / `720p` / `1080p`：**只缩小、不放大**。窗口比目标还小时保持原样，因为放大只会浪费 token 而不增加信息量。
- `原始`：完全不缩放。
- 分辨率越低，请求越快越省 token，但字太小的画面可能识别不准。

> 实测：一张 1280×720 的图压到 **360p**，JPEG 约 **14 KB**，模型依然能准确读出画面里的文字。

---

## 配置文件

`config/mcai.json`

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `api_key` | `""` | **必填**，你的 API Key |
| `api_url` | `https://api.deepseek.com/v1` | 接口地址，自动补 `/chat/completions` |
| `model` | `deepseek-flash` | 当前使用的模型 |
| `available_models` | `["deepseek-flash", "deepseek-v4-pro"]` | 「模型切换器」右键循环的列表 |
| `mode` | `chat` | 当前模式 |
| `available_modes` | `["chat", "vision"]` | 「模式切换器」右键循环的列表 |
| `vision_resolution` | `720p` | `H` 截图的目标分辨率 |
| `show_reasoning` | `true` | 是否在聊天栏显示思维链 |
| `reasoning_max_chars` | `500` | 思维链最多显示多少字；**设为 `0` 显示完整思考**（可能刷屏，实测单个问题可达 4000~6600 字） |
| `daily_tokens` | `0` | 今日消耗，程序自动维护 |
| `token_date` | 当天日期 | 用于跨天清零，程序自动维护 |

### Token 使用明细

除了 `daily_tokens` 的今日总数，每次调用还会追加一条明细到：

```
config/mcai-usage.csv
```

```csv
time,model,source,tokens
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

产物在 `build/libs/mcai-1.0.0.jar`。

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
| `StringArgumentType.word()` 接收任意文本 | **只接受 ASCII**，中文参数（如 `原始`）会解析失败，必须用 literal 节点 |
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
