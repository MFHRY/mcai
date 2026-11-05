# mcAI — Fabric 26.1（Minecraft 26.1.2）

本文记录把 mcAI 从 **Forge 26.1**（`mcai-26.1`，Minecraft 26.1.2 / Forge 64.1.5）
搬到 **Fabric 26.1** 的决策与差异。

## 0. 一句话总结

Minecraft 26.1 起 Mojang **不再混淆**游戏代码：没有 Yarn、没有 intermediary，
Loom 直接给你带官方（Mojang）名称的原版 jar。所以 **Forge 26.1 的源码和
Fabric 26.1 共用同一套游戏 API 名称**，只有"模组装配层"（入口点、事件、注册、
网络）是加载器自己的那套。本分支 = `mcai-26.1` 的源码 + Fabric 装配层。

## 1. 工具链（和 1.21.11 Fabric 版的差异）

| 项 | 1.21.11 Fabric | 26.1 Fabric |
| --- | --- | --- |
| Minecraft | 1.21.11 | **26.1.2**（`~26.1.2`） |
| 映射 | `yarn 1.21.11+build.6` | **无**（游戏自带官方名，`mappings` 一行都别写） |
| Loom | `1.19.3` | **`1.19-SNAPSHOT`**（release 1.19.3 硬性要求 `mappings` 配置，报
  `Configuration 'mappings' has no dependencies`；官方 26.1.2 示例模组就钉在 snapshot） |
| Loader | 0.20.1 | 0.20.1 |
| Fabric API | 0.141.6+1.21.11 | **0.155.3+26.1.2** |
| Mod Menu | 17.0.1 | **18.0.2** |
| Java | 21 | **25**（`options.release=25`，26.1 运行时就是 Java 25） |

Loom 插件 id 必须用**全限定** `net.fabricmc.fabric-loom`：短 id `fabric-loom`
只从 Gradle Plugin Portal 解析，而那边没有 snapshot。

## 2. Fabric API 装配（本分支特有的坑）

Fabric API 以**一个聚合 jar** 发布，真正的模块在它内部的
`META-INF/jars/*.jar` 里。Loom 通常在 `modImplementation` 配置下自动拆开，
但 **1.19-SNAPSHOT 没有注册 `modImplementation` / `modCompileOnly`**（26.1 不再
混淆，也就没有"mod 依赖需重映射"这件事）。因此本工程：

- 用独立配置 `fabricApiModules` 拉住聚合 jar（`transitive=false`）；
- 用 `tasks.unpackFabricApi`（`Copy` + `zipTree`）把模块拆到
  `build/fabric-api-modules/`；
- 把**模块 jar 本身**（不是目录）挂到 `compileClasspath`——javac 不会搜索
  目录里的 jar，必须用 `fileTree`，且 `compileJava` 依赖 `unpackFabricApi`。

运行期不用管：`fabric.mod.json` 声明 `fabric-api` 依赖，加载器自己会拆聚合 jar。

## 3. Forge → Fabric 装配层映射

| Forge 26.1 | Fabric 26.1 |
| --- | --- |
| `@Mod` 构造器（`FMLJavaModLoadingContext`） | `ModInitializer.onInitialize()`（`main` 入口点） |
| `FMLEnvironment.dist == CLIENT` 里装配客户端 | `ClientModInitializer.onInitializeClient()`（`client` 入口点） |
| `RegisterKeyMappingsEvent.BUS` + `event.register(...)` | `KeyMappingHelper.registerKeyMapping(...)`（一步到位） |
| `AddGuiOverlayLayersEvent`（`ForgeLayer.extract`） | `HudElementRegistry.attachElementAfter(...)`（`HudElement#extractRenderState`） |
| `TickEvent.ClientTickEvent.Post.BUS` | `ClientTickEvents.END_CLIENT_TICK` |
| `RegisterClientCommandsEvent`（`CommandDispatcher<CommandSourceStack>`） | `ClientCommandRegistrationCallback.EVENT`（`CommandDispatcher<FabricClientCommandSource>`） |
| `ClientChatEvent`（返回值 true = 取消） | `ClientSendMessageEvents.ALLOW_CHAT`（返回值 false = 吃掉） |
| `ClientPlayerNetworkEvent.LoggingIn/Out.BUS` | `ClientPlayConnectionEvents.JOIN / DISCONNECT` |
| `PlayerInteractEvent.RightClickItem` | `ItemEvents.USE`（返回 `InteractionResult`） |
| `PlayerEvent.PlayerLoggedInEvent.BUS` | `ServerPlayConnectionEvents.JOIN` |
| `DeferredRegister.create(ForgeRegistries.ITEMS,...)` | `Registry.register(BuiltInRegistries.ITEM, key, item)` |
| `ChannelBuilder...SimpleChannel` | `PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC)` + `ServerPlayNetworking` / `ClientPlayNetworking` |
| `FMLPaths.CONFIGDIR` / `ModList` | `FabricLoader.getInstance().getConfigDir()` / `getAllMods()` |

## 4. 26.1 游戏 API 的具体改动（两个分支共有的）

- `Item.Properties` 必须 `setId(ResourceKey<Item>)`，否则注册表冻结时抛
  `Item id not set`（1.21.2+ 起）。
- `KeyMapping` 的分类是 `KeyMapping.Category`（record），要
  `KeyMapping.Category.register(Identifier)` 一次，且只登记一份
  （`client/KeyCategories.java`）；翻译键 `key.categories.mcai.keys` 已补进
  8 个语言文件。
- 物品模型：1.21.4+ 需要 `assets/mcai/items/<id>.json`
  （`{"model":{"type":"minecraft:model","model":"mcai:item/<id>"}}`），
  否则物品渲染成**紫黑错误贴图**。本分支已带；`tools/check_jar.py` 校验。
- 文字颜色：`drawText` 读 alpha，`0xFFFFFF` = 全透明白。所有颜色都写成
  `0xFF......`；`tools/check_text_colors.py` 校验（本分支修掉了
  `ConfigScreen` 里一处 `0xA0A0A0`）。
- 注册表：`Registry.register` 第一参数要注册表对象（`BuiltInRegistries.ITEM`），
  不是 `Registries.ITEM`（后者只是 `ResourceKey`）。

## 5. 为什么没有 `pack.mcmeta`

Fabric 用 `fabric.mod.json` 管理资源，不需要 `pack.mcmeta`（那是 Forge 的
资源包格式）。`tools/check_jar.py` 对 Fabric 分支不要求它。
