# 更新日志 / Changelog

mcAI 的所有版本改动，**最新版本在最上面**。

版本号与 Modrinth / GitHub Release 上的版本号一致。配置文件 config/mcai.json
在所有版本之间**向前兼容**，升级不需要重新配置。

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
that the version cannot be parsed. Only `mcai-1.21.jar` goes in `mods`.

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
无法解析。放进 `mods` 的只能是 `mcai-1.21.jar`。

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