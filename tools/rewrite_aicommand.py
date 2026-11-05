"""Rewrite AiCommand.register() into the two Forge entry points.

Fabric hands the mod a callback registration:
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> { ... });
Forge hands over the dispatcher directly:
    onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher)

and the per-tick work moves to onClientTick(Minecraft). Splitting them also lets
McaiModClient own the event wiring instead of this class registering itself.

The body currently sits one level deeper (inside the old lambda), so it is
dedented as part of the move - otherwise the method reads as if it were still
nested. Run from the repository root:
    python tools/rewrite_aicommand.py
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "src", "main", "java", "com", "example", "mcai", "client", "AiCommand.java")

OLD_HEADER_MARK = "public static void register() {"
CALLBACK_MARK = "ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {"
CLOSE_MARK = "dispatcher.register(root);"

NEW_HEADER = '''    /**
     * 由 {@code McaiModClient} 的统一 tick 分发器调用：把"待打开的界面"落地。
     *
     * <p>为什么不能在指令里直接 {@code setScreen}：客户端指令<b>是在聊天界面里</b>
     * 执行的，聊天界面执行完还会自己 {@code setScreen(null)}，会把我们刚开的界面覆盖掉。
     * 所以这里只登记意图，等下一个 tick 再真正打开。
     */
    public static void onClientTick(Minecraft client) {
        Runnable action = pendingScreen;
        if (action != null) {
            pendingScreen = null;
            action.run();
        }
    }

    /**
     * 注册 {@code /ai} 全部子指令。
     *
     * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 通过
     * {@code ClientCommandRegistrationCallback} 把 dispatcher 交给我们；Forge 的
     * {@code RegisterClientCommandsEvent} 直接给 dispatcher，签名就是下面这样。
     * 两边都是<b>原生客户端指令</b>，不需要 mixin，也不会要求服务端装模组。
     */
    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {'''

HELPERS = '''
    // -------------------------------------------------- 回显助手（Forge 专用）

    /**
     * 发一条普通回显。
     *
     * <p>Fabric 的 {@code CommandSource.sendFeedback(Text)} 在 Forge 里叫
     * {@code sendSuccess(Supplier<Component>, boolean)} —— 多了一层 Supplier，
     * 而且多一个"是否广播给其他管理员"的参数。包成这两个小助手之后，
     * 下面 80 多处调用点只要换前缀，不用去动括号配对。
     */
    private static void feedback(CommandSourceStack source, Component text) {
        source.sendSuccess(() -> text, false);
    }

    /** 发一条错误回显。{@code sendError} 在 Forge 里叫 {@code sendFailure}。 */
    private static void error(CommandSourceStack source, Component text) {
        source.sendFailure(text);
    }
'''


def main() -> int:
    with io.open(PATH, encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    header_index = next(i for i, line in enumerate(lines) if OLD_HEADER_MARK in line)
    callback_index = next(i for i, line in enumerate(lines) if CALLBACK_MARK in line)
    close_index = next(i for i, line in enumerate(lines) if CLOSE_MARK in line)

    # the line after `dispatcher.register(root);` is the `});` that closed the lambda
    lambda_close_index = close_index + 1
    if lines[lambda_close_index].strip() != "});":
        print(f"unexpected line after dispatcher.register(root): {lines[lambda_close_index]!r}")
        return 1

    body = lines[callback_index + 1:lambda_close_index]
    # dedent one level: the body was inside a lambda, now it is a method body
    dedented = []
    for line in body:
        dedented.append(line[4:] if line.startswith("    ") else line)

    rebuilt = (
        lines[:header_index]
        + NEW_HEADER.split("\n")
        + dedented
        + ["    }"]
        + lines[lambda_close_index + 1:]
    )

    # insert the helper methods just before buildResolutionNode
    anchor = next(i for i, line in enumerate(rebuilt)
                  if "buildResolutionNode()" in line and "private static" in line)
    rebuilt = rebuilt[:anchor] + HELPERS.split("\n") + rebuilt[anchor:]

    with io.open(PATH, "w", encoding="utf-8", newline="") as handle:
        handle.write("\n".join(rebuilt))

    print(f"rewrote AiCommand: header at line {header_index + 1}, "
          f"lambda at {callback_index + 1}, body {len(dedented)} lines dedented")
    return 0


if __name__ == "__main__":
    sys.exit(main())
