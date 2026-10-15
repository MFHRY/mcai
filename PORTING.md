# Forge 1.20.1 port — mapping reference

This branch is the **Forge** port of mcAI. `main` is the Fabric version.

The single biggest source of mistakes here is that **Forge uses Mojang official
mappings, Fabric uses Yarn**. The same class has a different name, in a different
package. Nothing "looks wrong" — it just will not compile.

Keep this table open while porting. If you are about to write a Minecraft type
whose name is in the left column, use the right column instead.

## Class renames (Yarn → Mojang official)

| Yarn (Fabric, `main`) | Mojang (Forge, this branch) |
| --- | --- |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `net.minecraft.text.MutableText` | `net.minecraft.network.chat.MutableComponent` |
| `net.minecraft.text.Style` | `net.minecraft.network.chat.Style` |
| `net.minecraft.text.ClickEvent` | `net.minecraft.network.chat.ClickEvent` |
| `net.minecraft.text.HoverEvent` | `net.minecraft.network.chat.HoverEvent` |
| `net.minecraft.util.Identifier` | `net.minecraft.resources.ResourceLocation` |
| `net.minecraft.util.Formatting` | `net.minecraft.ChatFormatting` |
| `net.minecraft.util.math.BlockPos` | `net.minecraft.core.BlockPos` |
| `net.minecraft.util.math.MathHelper` | `net.minecraft.util.Mth` |
| `net.minecraft.util.hit.HitResult` | `net.minecraft.world.phys.HitResult` |
| `net.minecraft.util.hit.BlockHitResult` | `net.minecraft.world.phys.BlockHitResult` |
| `net.minecraft.util.hit.EntityHitResult` | `net.minecraft.world.phys.EntityHitResult` |
| `net.minecraft.registry.Registries` | `net.minecraft.core.registries.BuiltInRegistries` |
| `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
| `net.minecraft.client.gui.DrawContext` | `net.minecraft.client.gui.GuiGraphics` |
| `net.minecraft.client.gui.screen.Screen` | `net.minecraft.client.gui.screens.Screen` |
| `net.minecraft.client.gui.widget.ButtonWidget` | `net.minecraft.client.gui.components.Button` |
| `net.minecraft.client.gui.widget.TextFieldWidget` | `net.minecraft.client.gui.components.EditBox` |
| `net.minecraft.client.option.KeyBinding` | `net.minecraft.client.KeyMapping` |
| `net.minecraft.client.util.InputUtil` | `com.mojang.blaze3d.platform.InputConstants` |
| `net.minecraft.client.texture.NativeImage` | `com.mojang.blaze3d.platform.NativeImage` |
| `net.minecraft.client.texture.NativeImageBackedTexture` | `net.minecraft.client.renderer.texture.DynamicTexture` |
| `net.minecraft.client.sound.PositionedSoundInstance` | `net.minecraft.client.resources.sounds.SimpleSoundInstance` |
| `net.minecraft.sound.SoundEvents` | `net.minecraft.sounds.SoundEvents` |
| `net.minecraft.sound.SoundEvent` | `net.minecraft.sounds.SoundEvent` |
| `net.minecraft.item.ItemStack` | `net.minecraft.world.item.ItemStack` |
| `net.minecraft.item.Item` | `net.minecraft.world.item.Item` |
| `net.minecraft.item.Items` | `net.minecraft.world.item.Items` |
| `net.minecraft.block.Block` | `net.minecraft.world.level.block.Block` |
| `net.minecraft.block.BlockState` | `net.minecraft.world.level.block.state.BlockState` |
| `net.minecraft.entity.Entity` | `net.minecraft.world.entity.Entity` |
| `net.minecraft.entity.LivingEntity` | `net.minecraft.world.entity.LivingEntity` |
| `net.minecraft.entity.mob.HostileEntity` | `net.minecraft.world.entity.monster.Enemy` (interface) |
| `net.minecraft.entity.player.PlayerEntity` | `net.minecraft.world.entity.player.Player` |
| `net.minecraft.entity.player.PlayerInventory` | `net.minecraft.world.entity.player.Inventory` |
| `net.minecraft.client.font.TextRenderer` | `net.minecraft.client.gui.Font` |
| `net.minecraft.client.util.Window` | `com.mojang.blaze3d.platform.Window` |

## Method / field renames

| Yarn | Mojang |
| --- | --- |
| `Text.literal(x)` | `Component.literal(x)` |
| `Text.translatable(k, args)` | `Component.translatable(k, args)` |
| `new Identifier(ns, path)` / `Identifier.of(ns, path)` | `new ResourceLocation(ns, path)` |
| `Identifier.ofVanilla(path)` | `new ResourceLocation(path)` |
| `Identifier.getNamespace()` | `ResourceLocation.getNamespace()` |
| `Identifier.getPath()` | `ResourceLocation.getPath()` |
| `stack.getName()` | `stack.getHoverName()` |
| `stack.isEmpty()` | `stack.isEmpty()` (same) |
| `block.getName()` | `block.getName()` (same) |
| `MinecraftClient.getInstance()` | `Minecraft.getInstance()` |
| `client.inGameHud.getChatHud().addMessage(t)` | `client.gui.getChat().addMessage(t)` |
| `client.currentScreen` | `client.screen` |
| `client.player` / `client.world` | `client.player` / `client.level` |
| `client.setScreen(s)` | `client.setScreen(s)` (same) |
| `client.textRenderer` | `client.font` |
| `client.getTextureManager()` | `client.getTextureManager()` (same) |
| `context.drawText(...)` | `context.drawString(...)` |
| `context.drawTextWithShadow(...)` | `context.drawString(...)` (shadow is default) |
| `context.drawTexture(id, x, y, u, v, w, h, tw, th)` | `context.blit(id, x, y, u, v, w, h, tw, th)` |
| `context.fill(...)` | `context.fill(...)` (same) |
| `context.drawBorder(...)` | `context.renderOutline(...)` |
| `keyBinding.wasPressed()` | `keyMapping.consumeClick()` |
| `InputUtil.isKeyPressed(win, code)` | `InputConstants.isKeyDown(win, code)` |
| `InputUtil.Type.KEYSYM` | `InputConstants.Type.KEYSYM` |
| `GLFW_KEY_*` (org.lwjgl.glfw.GLFW) | same (`org.lwjgl.glfw.GLFW`) |
| `screen.render(ctx, mx, my, delta)` | `screen.render(gui, mx, my, delta)` |
| `screen.renderBackground(ctx, mx, my, delta)` | `screen.renderBackground(gui, mx, my, delta)` |
| `screen.shouldPause()` | `screen.isPauseScreen()` |

## Fabric API → Forge equivalent

| Fabric | Forge 1.20.1 |
| --- | --- |
| `fabric.mod.json` | `META-INF/mods.toml` + `@Mod("mcai")` |
| `ModInitializer` / `ClientModInitializer` | one `@Mod` class, ctor + `FMLClientSetupEvent` / `FMLCommonSetupEvent` |
| `ClientTickEvents.END_CLIENT_TICK` | `TickEvent.ClientTickEvent` on `MinecraftForge.EVENT_BUS` |
| `ClientPlayConnectionEvents.JOIN` / `DISCONNECT` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` |
| `ClientSendMessageEvents.ALLOW_CHAT` | `ClientChatEvent` (cancel it to swallow the message) |
| `HudRenderCallback` | `RenderGuiEvent.Post` on `MinecraftForge.EVENT_BUS` |
| `KeyBindingHelper.registerKeyBinding` | `RegisterKeyMappingsEvent` on the **mod** event bus |
| `ClientCommandRegistrationCallback` | `RegisterClientCommandsEvent` on `MinecraftForge.EVENT_BUS` |
| `PayloadTypeRegistry` + `ServerPlayNetworking` | `SimpleChannel` (`NetworkRegistry.newSimpleChannel`) |
| `FabricLoader.getInstance().getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |
| `FabricLoader.getInstance().getAllMods()` | `ModList.get().getMods()` |

### Verified, not assumed

`RegisterClientCommandsEvent`, `ClientCommandHandler` and `ClientCommandSourceStack`
all exist in `forge-1.20.1-47.4.28`. Client-side commands — the whole `/ai` family —
therefore port **without a mixin**, which keeps the "client-side only, works on any
server" property intact.

## Rules

1. **Never** import from `net.minecraft.text.*` or `net.minecraft.util.Identifier` —
   those are Yarn names and do not exist under official mappings.
2. `org.lwjgl.*`, `com.mojang.blaze3d.*` and `com.mojang.brigadier.*` are not
   remapped by mappings; their names are the same in both loaders.
3. Minecraft 1.20.1 is **Java 17**. Do not use Java 21-only APIs (no records with
   pattern matching, no `SequencedCollection`, no virtual threads).
4. Where a Forge equivalent genuinely does not exist, prefer deleting the feature
   over pulling in a mixin — except for client commands, which are already covered
   natively (see above).
