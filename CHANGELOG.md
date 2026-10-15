# 更新日志 / Changelog

mcAI 的所有版本改动，**最新版本在最上面**。

版本号与 Modrinth / GitHub Release 上的版本号一致。配置文件 config/mcai.json
在所有版本之间**向前兼容**，升级不需要重新配置。

---

## 1.25 — 2026-10-15

Ten changes, most of them about **spending less** and **asking more naturally**.

### New

**Ask in more ways**

- **Crosshair inspect (`V`)** — look at a block or mob and press `V`. The mod sends its name
  *and* its registry id (`modid:block`), so the model can tell two different mods' "copper
  block" apart, and knows it is looking at modded content at all. Mobs also carry their health,
  which is the number that actually decides whether to fight.
- **One-command inventory analysis (`/ai inv`)** — your inventory as a compact text list, plus
  your own question. The same information as a screenshot costs roughly a tenth as much this way.
- **Gemini and Claude support** — the request/response layer is now split per vendor and
  normalises everything back into one shape, so token stats, costing and history keep working
  unchanged. `api_provider` is `auto` by default and infers the protocol from your `api_url`;
  `/ai provider gemini` forces it if the guess is wrong.

**Spend less**

- **What each call actually cost** — appended to every answer, computed from the `usage` the
  provider really returned rather than from the pre-flight estimate. Previously you only saw a
  rough number *before* sending, and never the real one after.
- **Automatic retry** — network failures and 408/425/429/5xx are retried with exponential
  backoff (default 2 retries, `/ai retry 0..5`). Other 4xx responses are deliberately **not**
  retried: the request itself is wrong, and retrying only makes you wait longer for the same error.

**Use it more comfortably**

- **Click any answer to copy it.** Coordinates and 「item names」 keep their own click actions;
  clicking anywhere else copies the whole reply. This works because the copy action sits on the
  root text node and Minecraft lets a child's own style win over its parent's.
- **Rebindable hotkeys** — `H` / `G` / `R` / `V` can all be changed from the in-game config
  screen. The binding is still stored by vanilla in `options.txt`; we deliberately keep **no**
  second copy in `mcai.json`, because two sources of truth for one keybind is a bug factory.
- **Searchable, grouped config screen** — `/ai config` is now split into collapsible sections
  with a search box at the top, instead of one long list.
- **A whale companion on your HUD** — squashes when you hold `Q`, and pops a bubble with
  today's spend, budget remaining, the peak/off-peak countdown, current model and persona,
  cache hits and your account balance. `/ai avatar off` hides it.
  The bubble is toggled with **`B`**, the widget is positioned with **`/ai avatar edit`**, and
  its picture is set with **`/ai avatar image <path to a PNG>`** — deliberately *not* with the
  mouse. A HUD element cannot receive clicks in vanilla: while you play the cursor is locked to
  the window centre, and the only way to consume mouse events is a mixin, which this mod does
  not ship. Rather than leave a click that can never fire, each action was given a route that
  actually works.
  **No artwork is bundled.** You point the widget at your own PNG with
  `/ai avatar image <path>`; the path is validated before it is saved, so a typo tells you so
  instead of silently making the widget vanish. Keeping the artwork out also keeps this
  project's CC0 licence covering nothing but code.

**Also**

- The on-join guide and `/ai help` now list **every** command. In 1.23 and 1.24 the built-in
  guide had fallen behind the command list, so translation, the chat trigger prefix, and all of
  1.24's commands were undiscoverable in game.

### Notes

- No config key was removed or renamed; an existing `config/mcai.json` keeps working untouched.
- The companion's default artwork is original to this project (`tools/make_avatar.py` draws it).
  Third-party widget art is **not** bundled — `avatar_image` exists so you can bring your own image.

---

### 中文版

### 新增

**更多提问方式**

- **准星查询（V 键）**——看着一个方块或生物按 V。发送的是它的名字**加上注册 ID**
  （`modid:block`），这样模型能分清两个模组各自的"铜块"，也知道自己面对的是模组内容。
  生物会额外带上血量——判断"要不要打"真正要看的就是这个数。
- **物品栏一键分析（`/ai inv`）**——把背包整理成一段紧凑的文字清单，再带上你的问题。
  同样的信息如果发截图，费用大约是这种方式的十倍。
- **支持 Gemini 和 Claude**——请求/响应层按厂商拆开，再统一归一化回同一种形状，
  所以 token 统计、费用计算和多轮上下文都不用改。`api_provider` 默认 `auto`，
  按 `api_url` 自动判断；猜错了可以用 `/ai provider gemini` 强制指定。

**更省钱**

- **每一次调用的真实花费**——附在每条回答末尾，按服务商实际返回的 `usage` 结算，
  而不是发请求前的粗估。以前只有发送前的预估，事后花了多少反而看不到。
- **失败自动重试**——网络异常和 408/425/429/5xx 会按指数退避重试（默认重试 2 次，
  用 `/ai retry 0..5` 调整）。其余 4xx **刻意不重试**：请求本身就是错的，
  重试只会让你多等一会儿，然后看到同样的错误。

**用起来更顺手**

- **点一下复制整段回答**。坐标和「物品名」保留自己的点击行为，点其它地方则复制整条回答。
  能这么做是因为复制动作挂在根文本节点上，而 Minecraft 允许子节点用自己的样式覆盖父节点。
- **快捷键可自定义**——`H` / `G` / `R` / `V` 都能在游戏内设置界面里改。
  绑定仍然由原版存在 `options.txt` 里；我们**刻意不在 `mcai.json` 里再存一份**，
  因为同一个按键有两个真相来源，就是在制造 bug。
- **可搜索、已分组的设置界面**——`/ai config` 从一长条列表改成分组折叠 + 顶部搜索框。
- **HUD 上的鲸鱼娘挂件**——按住 `Q` 会 Q 弹；气泡显示今日花费、预算剩余、峰谷倒计时、
  当前模型与人格、缓存命中数和账户余额。`/ai avatar off` 可以关掉。
  气泡用 **`B`** 开关，位置用 **`/ai avatar edit`** 调，图片用
  **`/ai avatar image <PNG 路径>`** 指定——**刻意不做成鼠标点击**。
  原版里 HUD 元素收不到点击：游戏进行中光标被锁在窗口中心，而消费鼠标事件只能靠 mixin，
  本模组不打算引入。与其留一个永远不会触发的点击，不如把每件事都放到真正走得通的路上。
  **不打包任何美术素材**：用 `/ai avatar image <路径>` 指定你自己的 PNG。
  路径会**先校验再保存**，所以填错了会当场告诉你，而不是让挂件悄悄消失。
  不内置图片也让本项目的 CC0 只覆盖代码，不牵扯任何需要解释来源的素材。

**顺带**

- 进服提示和 `/ai help` 现在列出**全部**指令。1.23 和 1.24 里这份内置清单一度落后于
  实际指令，导致翻译、聊天触发词前缀以及 1.24 的全部新指令在游戏内都无法被发现。

### 说明

- 没有删除或改名任何配置项，旧的 `config/mcai.json` 可以直接用。
- 挂件的默认图片是本项目原创的（见 `tools/make_avatar.py`）。
  **没有**打包任何第三方挂件的美术素材——`avatar_image` 就是留给你换成自己的图的。

---

## 1.24 — 2026-10-12

The biggest update since 1.20: **13 new features**, most of them about spending less money
and sending less of your screen to a third party.

### New

**Cost control**

- **Selectable region screenshots (`R`)** — drag a box and only that part is sent. Image
  tokens scale with pixel count, so framing 40% of the screen costs 40% of the image.
- **Pre-send preview** — see the exact processed image (cropped, masked, resized) before it
  leaves your machine. Off by default: `/ai preview on`.
- **Privacy mask** — the chat area is blacked out before upload, so chat logs and player
  names never reach the provider. Off by default: `/ai mask on`.
- **Spend estimate before every request** — one dim line telling you roughly what this
  question will cost.
- **Budget warning at 80%** — previously the only feedback was being blocked *after* the
  budget was gone.
- **Local answer cache** — asking the same question twice costs nothing the second time.

**Better answers**

- **Modlist + version awareness** — the system prompt now carries the game version and the
  loaded mod list, so the model stops answering "that does not exist in vanilla" when you
  are clearly running a modpack. `/ai env off` disables it.
- **Long-term memory** — `/ai remember <text>` keeps facts (base coordinates, current
  project) in every request. `/ai memories`, `/ai forget <n>`, `/ai forget all`.

**Control and safety**

- **Cancel a request** — `ESC` (or `/ai cancel`). Previously a request could only be waited
  out or timed out at 60–90 seconds, and the model kept generating — and billing — the
  whole time. Cancelling now really aborts the HTTP exchange.
- **Danger alert** — optionally screenshot and ask for advice when health is low with
  hostile mobs nearby. `/ai danger on`. Fires only on the moment health crosses the
  threshold, then enforces a cooldown, so it cannot spam.
- **Clickable coordinates in answers** — coordinates are copied to the clipboard on click;
  「item names」 are offered as a chat suggestion. Clicking only copies or fills the box,
  never executes anything.
- **Multi-turn context is now per world/server** — this fixes a real bug: history used to be
  a single global queue, so after leaving a singleplayer world and joining a server the AI
  still remembered the previous world's coordinates.
- **Mod Menu integration** — the settings screen is now reachable from Mod Menu, not just
  from `/ai config`.
- **Text-to-speech** — read answers out loud via the OS speech synthesizer
  (`/ai tts on`, `/ai speak <text>`). For accessibility and for `/ai describe`.

### Fixed in this release

- `fabric.mod.json` still advertised "(English / Chinese)" after 1.23 added 繁體中文 and
  日本語. It now lists all four languages.
- The vision path used a bare system prompt with no persona; chat and screenshots now build
  the system prompt through the same code (`SystemPrompt`), so they cannot drift apart.

### 中文版

自 1.20 以来最大的一次更新：**13 个新功能**，其中大部分是围绕"少花钱"和"少把画面交给第三方"。

### 新增

**费用控制**

- **框选截图（`R`）**：拖框，只把框住的部分发出去。图片 token 与像素数成正比，
  框住 40% 的面积就只花 40% 的图片费用。
- **发送前预览**：在图片离开你的电脑之前，先看到**处理之后**的样子（已裁剪、已遮罩、已缩放）。
  默认关闭，`/ai preview on` 打开。
- **隐私遮罩**：上传前把聊天栏那块涂黑，聊天记录和玩家名不会送到服务商。默认关闭，`/ai mask on`。
- **每次请求前给花费预估**：一行暗色提示，告诉你这一问大概要花多少钱。
- **预算 80% 提醒**：以前唯一的提示是"钱花完了被拦下"，中途完全没有预警。
- **本地回答缓存**：同一个问题问第二遍不再花钱。

**回答质量**

- **模组与版本感知**：系统提示词现在会带上游戏版本和已加载的模组列表，
  你明显在玩整合包时它不会再回答"原版没有这个东西"。`/ai env off` 可关。
- **长期记忆**：`/ai remember <内容>` 记下基地坐标、正在建的东西等，之后每次提问都会带上。
  用 `/ai memories` 查看，`/ai forget <序号>` / `/ai forget all` 删除。

**控制与安全**

- **中止请求**：按 `ESC`（或 `/ai cancel`）。以前发出去只能干等 60–90 秒超时，
  而且模型那边**一直在生成、一直在计费**；现在取消会真正中断 HTTP 连接。
- **危险预警**：血量低且附近有敌对生物时，可选自动截图求助。`/ai danger on` 打开。
  只在血量跨过阈值的那一刻触发一次并强制冷却，不会刷屏。
- **回答里的坐标可点击**：点坐标复制到剪贴板，点「物品名」把它填进聊天栏。
  点击只做复制或填入，不会直接执行任何操作。
- **多轮上下文按世界/服务器隔离**：修掉一个真实缺陷——上下文原来是一个全局队列，
  离开单人存档进服务器之后，AI 还记得上一个世界的坐标。
- **Mod Menu 集成**：设置界面现在也能从 Mod Menu 进入，不再只能 `/ai config`。
- **语音朗读**：用系统语音合成把回答念出来（`/ai tts on`、`/ai speak <文本>`），
  服务于无障碍需求和 `/ai describe`。

### 本次顺带修掉的问题

- `fabric.mod.json` 在 1.23 加入繁體中文和日本語之后仍写着 "(English / Chinese)"，
  现在四种语言都列上了。
- 截图链路以前用的是不带人格的裸系统提示词，聊天和截图现在统一走 `SystemPrompt` 拼装，
  两条链路不会再各自漂移。

---

## 1.23 — 2026-10-12

Two new languages. Nothing else changed — no behaviour changes, no config changes.

### New

- **繁體中文 (zh_tw)** — a full Traditional Chinese translation, 258 strings. Written for
  Taiwanese conventions rather than a character-by-character conversion: 螢幕 / 設定 /
  儲存 / 伺服器 / 網路 / 生態域 / 座標. The AI system prompts are localized too, so the
  model answers in Traditional Chinese.
- **日本語 (ja_jp)** — a full Japanese translation, 258 strings. The AI system prompts,
  persona suffixes and translation prompts are localized as well, so the model answers in
  Japanese without you having to change any setting.

The mod now ships **four** languages in one jar: English, 简体中文, 繁體中文, 日本語.
It follows your Minecraft language setting automatically.

### 中文版

新增两个语言，其他一律没动——行为没有变化，配置文件也没有变化。

### 新增

- **繁體中文（zh_tw）**：完整的繁體中文翻译，258 条。是按台湾用语习惯写的，不是逐字转换：
  螢幕 / 設定 / 儲存 / 伺服器 / 網路 / 生態域 / 座標。AI 系统提示词也一并本地化，
  模型会直接用繁體中文回答。
- **日本語（ja_jp）**：完整的日语翻译，258 条。AI 系统提示词、人格后缀和翻译提示词
  也一并本地化，切到日语后模型直接用日语回答，不需要改任何设置。

现在同一个 jar 里带 **四种语言**：English、简体中文、繁體中文、日本語，跟随游戏语言自动切换。

---

## 1.22 — 2026-10-10

A cleanup + quality-of-life release on top of 1.21: three new features, and the internal
fixes found while reviewing the codebase.

### New

- **Configurable trigger prefix** — `!ai` is no longer hardcoded. `/ai prefix <value>`
  (e.g. `/ai prefix @ai`) changes what starts an AI chat; `/ai status` shows it.
- **AI translation** — `/ai tl <text>` translates to the default target language,
  `/ai tl <lang> <text>` to a specific one (`/ai translate` is an alias), and
  `/ai tlang <lang>` sets the default target language.
- **Persona management** — `/ai persona` shows the current persona, `/ai persona list`
  lists all presets (builder / redstone / survival / english / default).

### Fixed

- Anthropic model ids in the model catalog were empty strings; real ids are now filled in,
  and blank ids are rejected outright.
- `TokenStats` writes through the same copy-on-write `ConfigManager.update` as everything
  else; the daily rollover read path no longer mutates config.
- `ApiKeyManager` returns a `PickedKey` handle so exactly the key used in a request gets
  blacklisted on failure; the dead-key set is now thread-safe.
- The 8-second cooldown is only consumed after every validation passes (config ready, key
  present, budget OK) — no more "cooldown eaten by a request that never ran".
- `ConfigManager` waits for the initial disk load before applying edits, removing a startup
  race that could overwrite `mcai.json`.
- Removed the never-enabled `ExampleMixin` and its mixins config.
- Dead code cleaned from `VisionHandler` / `ChatHandler`.
- `RecipeHelper` craftability now uses bipartite maximum matching instead of greedy
  consumption, so substitutable materials (e.g. planks) are handled correctly.
- `ItemSwitchHandler` only sends the c2s payload when the channel is actually open.
- README usage-CSV sample rows updated to the real 5-column format.
- `ChatHandler` moved into the client package (the only client-only class that lived in the
  common package).

### 中文版

在 1.21 基础上的一次「内部清理 + 三个新功能」更新。

### 新增

- **可配置触发前缀**：`!ai` 不再写死，`/ai prefix <值>`（如 `/ai prefix @ai`）即可修改，
  `/ai status` 会显示当前值。
- **AI 翻译**：`/ai tl <文本>` 翻成默认目标语言，`/ai tl <语言> <文本>` 指定语言
  （`/ai translate` 是别名），`/ai tlang <语言>` 设置默认目标语言。
- **人格管理**：`/ai persona` 查看当前人格，`/ai persona list` 列出全部预设。

### 修复

- 模型目录里 Anthropic 的 5 个模型 id 原本是空字符串，已补成真实 id，并加入空值校验。
- `TokenStats` 统一走 `ConfigManager.update` 的写时复制；跨天清理由“读时清零”改为只读判断。
- `ApiKeyManager` 返回 `PickedKey` 句柄，失败时精确拉黑本次请求用掉的那把 key；
  失效 key 集合改为线程安全。
- 8 秒冷却移到**所有校验通过之后**才消耗，不再出现“配置没填好还先扣冷却”的问题。
- `ConfigManager` 会先等磁盘配置加载完成再应用修改，消除启动竞态覆盖 `mcai.json` 的隐患。
- 移除从未启用的 `ExampleMixin` 及 mixins 配置。
- 清理 `VisionHandler` / `ChatHandler` 中的死代码。
- `RecipeHelper` 可合成判定改用**二分图最大匹配**，正确处理可替代材料（如木板）。
- `ItemSwitchHandler` 只在对应网络通道可用时才发包。
- README 中用量 CSV 示例改成真实的 5 列格式。
- `ChatHandler` 移入 client 包（common 包里唯一的客端专属类）。

---

## 1.21 — 2026-10-09

> **1.20 有四个真实 bug，1.21 全部修复，建议所有 1.20 用户升级。**

> ## ⚠️ If you are on 1.20, please update.
>
> **1.20 has four real bugs, listed below.** None of them can damage your world or your
> account, but several features simply did nothing, and one of them (`/ai config`) has been
> broken since 1.1.0. **1.21 fixes all of them and adds in-game switches so you never have
> to hand-edit `mcai.json` again.**
>
> Your `mcai.json` is fully compatible — nothing to reconfigure.

---

### The bugs in 1.20, and what actually caused them

#### 1. `/ai config` did nothing at all — *broken since 1.1.0*

Typing `/ai config` produced no reaction. This was **not** a problem with the settings
screen itself.

A client command runs *inside* the chat screen, and the chat screen closes itself with
`setScreen(null)` **after** the command returns — which immediately overwrote the screen the
command had just opened. The screen is now opened on the next client tick, once the chat
screen is gone.

So this was broken in 1.1.0 too; it just had not been noticed yet.

#### 2. Streaming output was invisible

The streamed answer was written to the action bar, but the "AI is thinking…" spinner was
**also** writing to the action bar every 3 ticks. The two overwrote each other several times
a second, so all you ever saw was the spinner.

The spinner now yields the action bar while a stream is live. (Streaming itself was working
the whole time — you just could not see it.)

#### 3. Streaming lost the chain of thought

The normal path printed `[Thinking]` above the answer. The streaming path collected only the
answer and never printed the reasoning. Reasoning is now accumulated and rendered through the
same formatter, so both paths look and behave the same.

#### 4. The new features could only be turned on by editing `mcai.json`

This is what made several features look broken. **Death recap is off by default** (it sends
your screen away automatically, so it must be opt-in) — but with no in-game switch, "off by
default" meant "appears to do nothing, forever". Same story for the daily budget, the
context length and the Discord webhook.

**Fixed: everything is now reachable from inside the game.**

```
/ai death on|off           Death recap
/ai streaming on|off       Streaming output
/ai recipe on|off          Local recipe answers
/ai budget <CNY>           Daily budget (0 = unlimited)
/ai history <turns>        Conversation turns kept in memory
/ai discord <url|clear>    Discord webhook
```

…and `/ai config` now works and contains the three switches plus the budget and
context-length fields.

#### Also fixed

- `/ai craft <item>` now looks up a recipe **by name** (`/ai craft diamond pickaxe`). In 1.20
  the lookup helper existed but was never connected to a command, so "recipe matching" did
  nothing. `/ai craft` with no argument still lists what your inventory can build right now.
- The recipe scan now logs how many recipes it examined, so a genuine failure can be told
  apart from "you really do not have the materials".

#### One packaging note

**Do not put the `-sources.jar` in your `mods` folder.** It ships a `fabric.mod.json` with the
same mod id and an unexpanded `${version}`, so Fabric sees two mods named `mcai` and warns
that the version cannot be parsed. Only `mcai-1.22.jar` goes in `mods`.

---

### Everything from 1.20 is still here

This release is a bug-fix release: no feature was removed.

- Local models (Ollama / LM Studio) need no API key — free and offline
- Cost estimate in CNY from the official DeepSeek price table (peak / off-peak) with a hard
  daily budget
- Multiple API keys with automatic failover on 401 / 402 / 429
- Multi-turn context, kept in memory only, `/ai clear` to forget
- Persona presets: `/ai persona builder | redstone | survival | english`
- `H` screenshot, `G` build/redstone analysis, `/ai describe` screen reading
- Automatic crop to the open container GUI (image tokens drop to roughly a quarter)
- `/ai read` sign and book translation (text only, so it is cheap and accurate)
- `/ai where` world context, `/ai task` inventory tasks, `/ai chart` 7-day usage
- `/ai craft` zero-token recipe help
- Death recap and Discord broadcast (both off by default — now switchable in game)
- 247 fully localized strings, English and Chinese, in the same jar

---

### 中文版
> ## ⚠️ 如果你在用 1.20,请务必更新。
>
> **1.20 有四个真实的 bug,下面逐条列出。** 它们都不会损坏你的存档或账号,但有几个功能
> 实际上完全没生效,其中 `/ai config` **从 1.1.0 起就一直是坏的**。
> **1.21 修好了全部四个,并且把开关都做进了游戏里,以后不用再手改 `mcai.json`。**
>
> 你的 `mcai.json` 完全兼容,不需要重新配置。

---

### 1.20 的 bug,以及真正的原因

#### 1. `/ai config` 输入后毫无反应 —— **从 1.1.0 就坏了**

打 `/ai config` 没有任何反应。问题**不在**设置界面本身。

客户端指令是在**聊天界面内部**执行的,而聊天界面会在指令执行完之后调用 `setScreen(null)`
关闭自己——正好把指令刚打开的界面盖掉了。现在改成等**下一个 tick**(聊天界面已经关掉)
再打开。

所以这个问题 1.1.0 就存在,只是一直没被发现。

#### 2. 流式输出完全看不见

流式生成的正文写在头顶 Action Bar 上,但"AI 正在思考中…"的转圈动画**每 3 tick 也往同一
块地方写**。两者每秒互相覆盖好几次,所以你看到的永远只是转圈。

现在流式期间转圈会主动让出这块区域。(流式本身一直是正常工作的,只是你看不到。)

#### 3. 流式模式下思维链丢失

普通路径会在答案上方打印 `[思考]`,而流式路径只收集答案、从不打印思考过程。现在思维链
会被收集起来,并用**同一套格式化逻辑**渲染,两条链路表现一致。

#### 4. 新功能只能改 `mcai.json` 才能开

这就是让好几个功能看起来"没用"的原因。**死亡复盘默认是关闭的**(它会自动把画面发出去,
必须由玩家主动开启)——但游戏里没有任何开关,于是"默认关闭"就等于"永远毫无反应"。
每日预算、上下文轮数、Discord 播报也是一样。

**已修复:现在全都能在游戏里直接设置。**

```
/ai death on|off           死亡复盘
/ai streaming on|off       流式输出
/ai recipe on|off          本地配方回答
/ai budget <元>             每日预算(0=不限)
/ai history <轮数>          保留的对话轮数
/ai discord <网址|clear>    Discord webhook
```

……而且 `/ai config` 现在能打开了,里面有这三个开关,还有每日预算和上下文轮数两个输入框。

#### 其它修复

- `/ai craft <物品名>` 现在可以**按名字查配方**了(例如 `/ai craft 钻石镐`)。1.20 里那个
  查询函数写好了却忘了接上指令,所以"配方匹配"等于没有。不带参数的 `/ai craft` 仍然会
  列出你背包里材料够做的东西。
- 配方扫描现在会记录"一共检查了多少条配方",这样能区分是**真的读不到配方表**,还是
  **你确实材料不够**。

#### 一条打包提醒

**不要把 `-sources.jar` 放进 `mods` 文件夹。** 它里面带着一个 `fabric.mod.json`,mod id
相同、版本号是未经替换的 `${version}`,于是 Fabric 会看到两个叫 `mcai` 的 mod 并警告版本
无法解析。放进 `mods` 的只能是 `mcai-1.22.jar`。

---

### 1.20 的功能一个都没少

这是修 bug 的版本,没有删掉任何功能。

- 本地模型(Ollama / LM Studio)不需要 API Key——免费、可离线
- 按官方 DeepSeek 价格表估算人民币花费(高峰/空闲自动切换),带每日硬预算
- 多 Key 自动降级(401 / 402 / 429)
- 多轮上下文,只存内存,`/ai clear` 立即清除
- 人格预设:`/ai persona builder | redstone | survival | english`
- `H` 截图识别、`G` 建筑/红石分析、`/ai describe` 朗读屏幕
- 自动裁剪容器界面(图片 token 降到约四分之一)
- `/ai read` 告示牌/成书翻译(纯文本,又便宜又准)
- `/ai where` 环境上下文、`/ai task` 背包任务、`/ai chart` 七天用量
- `/ai craft` 零 token 配方助手
- 死亡复盘与 Discord 播报(默认关闭——现在可以在游戏里开关)
- 247 条完整本地化文案,中英文同一个 jar

---

## 1.20 — 2026-10-09

This is the biggest update so far: **16 new modules**, and the mod is now useful even
without paying for an API.

### New: it can be free

- **Local models need no API key.** Point the endpoint at Ollama or LM Studio
  (`http://localhost:11434/v1`) and mcAI works offline, for free.
- **`/ai craft` costs nothing.** It reads the recipe table the server already sent you and
  lists what you can build from your inventory right now. Asking `!ai how do I craft X` is
  answered locally too — no tokens, no network.
- **Discord broadcasts cost nothing** (webhook only, no model call).

### New: you can see what you spend

- **Cost in CNY, from the official DeepSeek price table**, with peak / off-peak rates
  applied automatically by Beijing time. `/ai cost` shows the rate and today's total.
- **A hard daily budget.** Set `daily_budget_yuan` and requests are refused once you reach
  it, instead of finding out at the end of the month.
- **`/ai chart`** draws your last 7 days of usage as a bar chart in chat.

### New: it keeps working when things go wrong

- **Multiple API keys with automatic failover.** Add `backup_api_keys` and mcAI rotates to
  the next one on 401 / 402 / 429.

### New: it remembers the conversation

- **Multi-turn context**, kept **in memory only** — nothing is written to disk, and
  `/ai clear` forgets it. Off by default? No: set `history_turns` to `0` to disable.
- **Persona presets**: `/ai persona builder|redstone|survival|english`.
- **Streaming output**: the answer appears live on the action bar as it is generated.

### New: more ways to use the camera

- **`G` key** — analyse the build or redstone circuit you are looking at.
- **`/ai describe`** — read the screen out loud, for players who cannot see it well.
- **Automatic GUI crop**: when you press `H` with a chest or inventory open, only the
  container is sent. Image tokens drop to roughly a quarter and the model reads the slots
  more reliably.
- **Death recap**: screenshot the death screen, get told what killed you and what to do
  differently. Optional, **off by default**. Can also post to a Discord webhook.

### New: cheaper ways to ask about text and places

- **`/ai read`** — translate the sign you are looking at, or the book you are holding.
  This sends **text, not an image**, so it is far cheaper and far more accurate than OCR.
- **`/ai where`** — sends your coordinates, biome, light level, dimension and health as
  context, so you can ask "what should I look for here?".
- **`/ai task`** — generates tasks from what is actually in your inventory.

### Everything is still bilingual

All 224 user-facing strings exist in both English and Chinese, in the same jar.

---

### 中文版
这是目前为止最大的一次更新：**16 个新模块**，而且不花钱也能用了。

### 新增：可以不花钱

- **本地模型不需要 API Key。** 把接口地址指向 Ollama 或 LM Studio
  （`http://localhost:11434/v1`）就能离线、免费使用。
- **`/ai craft` 不花一分钱。** 它读取服务器已经同步给你的配方表，列出你现在背包里
  的材料能做出的东西。用 `!ai 怎么做某某` 提问也会被本地直接回答——不消耗 token、
  不用联网。
- **Discord 播报不花钱**（只发 webhook，不调用模型）。

### 新增：能看见花费

- **按官方 DeepSeek 价格表估算人民币花费**，并自动按北京时间套用高峰 / 空闲两档单价。
  `/ai cost` 会显示当前单价和今日合计。
- **每日硬预算。** 设置 `daily_budget_yuan` 后，一旦达到上限就直接拒绝新请求，
  而不是等到月底才发现超支。
- **`/ai chart`** 在聊天栏画出最近 7 天的用量柱状图。

### 新增：出错时不中断

- **多 Key 自动降级。** 填上 `backup_api_keys`，遇到 401 / 402 / 429 会自动切到下一个。

### 新增：能记住上下文

- **多轮上下文**，**只存在内存里**——不写盘，`/ai clear` 立即忘记。
  把 `history_turns` 设为 `0` 即可关闭。
- **人格预设**：`/ai persona builder|redstone|survival|english`。
- **流式输出**：回答会随着生成实时显示在头顶 Action Bar。

### 新增：更多看画面的方式

- **`G` 键** —— 分析你正在看的建筑或红石电路。
- **`/ai describe`** —— 把屏幕内容念出来，方便看不清屏幕的玩家。
- **自动裁剪界面**：开着箱子或背包按 `H` 时，只把容器那一块发出去。图片 token 大约
  降到四分之一，模型也更容易看清格子里的东西。
- **死亡复盘**：截取死亡画面，告诉你被什么杀了、下次该注意什么。可选，**默认关闭**。
  也可以同时播报到 Discord webhook。

### 新增：更便宜地问文字和位置

- **`/ai read`** —— 翻译准星指向的告示牌，或你手中的成书。发的是**文本而不是图片**，
  所以比 OCR 便宜得多、也准得多。
- **`/ai where`** —— 把坐标、群系、光照、维度、生命值作为上下文发出去，
  于是可以问"这附近我该找什么？"。
- **`/ai task`** —— 根据你背包里真实有的东西生成任务。

### 仍然是双语的

224 条面向玩家的文案在中英文里都存在，同一个 jar。

---

## 1.1.0 — 2026-10-08

- 中英双语（同一个 jar 跟随游戏语言），新增英文语言文件
- 修复 Gson 无法序列化 java.time.LocalDate 导致配置根本不落盘的问题

## 1.0.0 — 2026-10-08

- 首个版本：\!ai\ 聊天问答、\H\ 截图识别、模型/模式切换器、Token 统计