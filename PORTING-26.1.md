# Forge 26.1 port — what changed and why

This branch is the **Minecraft 26.1** edition of mcAI:

| | branch | loader | Minecraft | Java |
| --- | --- | --- | --- | --- |
| Fabric | `main` | Fabric Loader | 1.21.1 | 21 |
| Forge (old) | `forge-1.20.1` | Forge 47.4.28 | 1.20.1 | 17 |
| Forge (this) | `forge-26.1` | Forge 64.1.5 | 26.1.2 | 25 |

`forge-1.20.1` renamed Yarn classes to Mojang official names; that part of the
work carries over unchanged (see `PORTING.md`). What follows is only what 26.1
itself broke.

## Toolchain

- **ForgeGradle 7 + Gradle 9.5.** The `minecraft { mappings channel: ... }` block
  is gone. Mappings come with the Forge artifact through the mavenizer:

  ```groovy
  repositories {
      minecraft.mavenizer(it)
      maven fg.forgeMaven
      maven fg.minecraftLibsMaven
  }
  dependencies {
      implementation minecraft.dependency('net.minecraftforge:forge:26.1.2-64.1.5')
      annotationProcessor 'net.minecraftforge:eventbus-validator:7.0.5'
  }
  ```

- **Java 25**, and set `options.release = 25` as well as the toolchain: the
  toolchain alone still lets a 21-only API slip in, and the resulting jar fails
  on a stock client with `UnsupportedClassVersionError`.

- **`net.minecraftforge.gradle.merge-source-sets=true` is mandatory for dev runs.**
  ForgeGradle 7 keeps its classic layout by default: `build/classes/java/main`
  plus `build/resources/main`. The Slime launcher hands FML one path per mod and
  scans it for `@Mod` classes — and that path is the *resources* dir, which holds
  no classes. The game then dies at startup with

  ```
  File .../build/resources/main constructed 0 mods: [], but had 1 mods specified: [mcai]
  ```

  `./gradlew build` is perfectly green while this happens, so it is easy to lose
  an hour to it. With the flag on, each source set's classes and resources are
  merged into `build/sourceSets/main` and the scan finds them.

## EventBus 7

There is no global `MinecraftForge.EVENT_BUS`. Every event owns its bus, and
cancellable events take a `Predicate<T>` listener where **returning `true`
cancels**:

```java
ClientChatEvent.BUS.addListener(event -> !ChatHandler.getInstance().onChatMessage(event.getMessage()));
PlayerInteractEvent.RightClickItem.BUS.addListener(ItemSwitchHandler::onRightClickItem);
```

`ForgeHooksClient.onClientSendMessage` returns `""` for a cancelled chat event,
so the message never reaches the server — the "works on any server" property
survives. `TickEvent.ClientTickEvent.Post.BUS`,
`RegisterClientCommandsEvent.BUS`, `ClientPlayerNetworkEvent.LoggingIn/Out.BUS`
and `RegisterKeyMappingsEvent.BUS` (mod bus) are the other registrations.

Also gone: `DistExecutor` (branch on `FMLEnvironment.dist == Dist.CLIENT`) and
`@Mod(value = ..., modid = ...)` — the annotation only takes the id now, and the
mod bus group comes from the constructor:

```java
public McaiMod(FMLJavaModLoadingContext context) {
    BusGroup modBus = context.getModBusGroup();
    ...
}
```

## Client API migrations

| 1.20.1 | 26.1 |
| --- | --- |
| `ResourceLocation` | `Identifier` (`fromNamespaceAndPath` / `parse` / `withDefaultNamespace`) |
| `ResourceKey.location()` | `ResourceKey.identifier()` |
| `GuiGraphics` | `GuiGraphicsExtractor` |
| `Screen.render(g, ...)` | `extractRenderState(g, ...)` |
| `renderBackground(g)` | `extractBackground(g, mx, my, delta)` |
| `drawString(...)` | `text(...)` |
| `drawCenteredString(...)` | `centeredText(...)` |
| `renderOutline(...)` | `outline(...)` |
| `renderTooltip(font, list, optional, x, y)` | `setTooltipForNextFrame(font, list, optional, x, y)` |
| `blit(id, x, y, u, v, w, h, tw, th)` | `blit(id, x0, y0, x1, y1, u0, u1, v0, v1)` — target rect + normalised uv |
| `context.pose()` → `PoseStack` | → `org.joml.Matrix3x2fStack` (`pushMatrix`/`popMatrix`, 2D translate/scale) |
| `mouseClicked(double, double, int)` | `mouseClicked(MouseButtonEvent, boolean doubleClick)` |
| `mouseScrolled(x, y, amount)` | `mouseScrolled(x, y, scrollX, scrollY)` |
| `keyPressed(int key, int scan, int mods)` | `keyPressed(KeyEvent)` — use `event.key()` |
| `EditBox.setFormatter(f)` | `EditBox.addFormatter(f)` — **additive**, installing it per relayout stacks masks |
| `KeyMapping(category: String)` | `KeyMapping.Category.register(Identifier)` — one shared instance, or the controls screen shows the category twice |
| `Screenshot.takeScreenshot(...) -> NativeImage` | `Screenshot.takeScreenshot(target, downscale, Consumer<NativeImage>)` — asynchronous |
| `NativeImage.getPixelRGBA/setPixelRGBA` | `getPixel/setPixel`, **ABGR** |
| `DynamicTexture(NativeImage)` | `DynamicTexture(Supplier<String> label, NativeImage)` |
| `AddGuiOverlayLayersEvent` absent | HUD layers: `event.getLayeredDraw().add(Identifier, ForgeLayer)` |
| `Item.Properties` without id | `setId(ResourceKey<Item>)` is required |
| `stack.is(item)` | gone — compare `stack.getItem() == item` |
| `appendHoverText(stack, level, list, flag)` | `(stack, TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` |
| `ChatComponent.addMessage` | `addClientSystemMessage` |
| `Level.getDayTime()` | `getOverworldClockTime()` |
| `SharedConstants.getCurrentVersion().getName()` | `.name()` |
| `ModList.get().getMods()` | `ModList.getMods()` |
| `InputConstants.isKeyDown(long handle, int key)` | `isKeyDown(Window, int key)` |
| `options.renderDebug` | `getDebugOverlay().showDebugScreen()` |
| written/writable books in NBT | `DataComponents.WRITTEN_BOOK_CONTENT` / `WRITABLE_BOOK_CONTENT` |
| `ClickEvent`/`HoverEvent` constructors | sealed records: `new ClickEvent.SuggestCommand(s)`, `new HoverEvent.ShowText(c)` |

## Recipes

The client no longer has a `RecipeManager` API: `Level.getRecipeManager()` is
gone and `Recipe` exposes neither `getIngredients()` nor `getResultItem()`.
What is left is the display pipeline:

```
ClientRecipeBook.getCollections()
  -> RecipeCollection.getRecipes()          // RecipeDisplayEntry
  -> display() / craftingRequirements() / resultItems(ContextMap)
  -> RecipeDisplay.result() -> SlotDisplay  // Item, ItemStack, Tag, Composite, WithRemainder...
```

`RecipeHelper` resolves those into `record Match(ItemStack result, List<String>
ingredients)`, which is what `/ai craft` and the offline "how do I make X"
answer consume. `Inventory.fillStackedContents(StackedItemContents)` replaces
the old `CraftingInventory`/`RecipeManager.getRecipeFor` probing for
"what can I craft right now".

## Resources

`pack.mcmeta` uses the new format fields. `pack_format` is dead above 64 for
resources; a value above that without `min_format`/`max_format` is rejected:

```json
{ "pack": { "description": "mcAI resources", "min_format": 84, "max_format": 101 } }
```

84 is the 26.1.2 resource format and 101 the data format (`version.json` inside
the client jar), and the range covers both because one file serves both packs.

## What was verified in a real client

Not just "it compiles":

- the mod loads and registers items, the network channel, 8 client listeners
  and the H/G/R/V/B key mappings;
- the HUD widget layer draws (it prints its "no avatar yet" hint on join);
- `H` captures a screenshot, the async callback fires and the vision handler
  reports the missing API key;
- `!ai ping` is intercepted locally — the message never reaches the server —
  and the request pipeline answers with the endpoint's 503 in chat;
- the config screen renders, accepts edits, saves, and reports the model
  switches;
- there were no exceptions anywhere in that session.
