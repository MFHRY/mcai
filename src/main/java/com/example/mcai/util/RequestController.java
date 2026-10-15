package com.example.mcai.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求生命周期控制（1.24 新增）：让玩家能中止一个已经发出去的请求。
 *
 * <p><b>为什么要它：</b>请求超时是 60 / 90 秒。以前发出去之后只能干等——
 * 问错了、或者模型卡住了，都没有任何办法停下来。
 *
 * <p><b>怎么做到"取消"：</b>给每次请求发一个自增的 id，并把它记为"当前请求"。
 * 回调（或流式循环）在输出任何东西之前先问一句 {@link #isCurrent(long)}：
 * <ul>
 *   <li>返回 false → 这次响应已经过期（被取消、或被新请求顶掉），<b>直接丢掉不显示</b>；
 *       这样即使底层 HTTP 已经无法真正中断，也不会有幽灵回答突然冒出来。</li>
 *   <li>返回 true → 正常显示，并调用 {@link #finish(long)} 收尾。</li>
 * </ul>
 * 同时把 {@link CompletableFuture} 登记进来，{@link #cancel()} 会尝试真正中断它
 * （对非流式请求有效；流式请求靠循环里的 isCurrent 检查跳出）。
 *
 * <p>所有状态都是静态的：mcAI 一次只允许一个进行中的请求，这和冷却机制是一致的。
 */
public final class RequestController {

    private RequestController() {}

    private static final AtomicLong SEQUENCE = new AtomicLong(0L);
    private static final AtomicLong CURRENT = new AtomicLong(0L);

    private static volatile CompletableFuture<?> inFlight;

    /**
     * 取消时要顺带执行的收尾动作（例如停掉"思考中"动画）。
     * 用回调而不是让 RequestController 直接依赖 ThinkingIndicator，
     * 是为了让这个类保持在 util 层、不反向依赖客户端 UI。
     */
    private static volatile Runnable cancelHook;

    /** 登记取消时的收尾动作；每次开始请求前设置。 */
    public static void setCancelHook(Runnable hook) {
        cancelHook = hook;
    }

    /** 开始一次新请求，返回它的 id。会让之前所有在途请求立即失效。 */
    public static long begin() {
        long id = SEQUENCE.incrementAndGet();
        CURRENT.set(id);
        inFlight = null;
        cancelHook = null;
        return id;
    }

    /** 这个 id 是否仍是当前请求（false = 已被取消或被顶掉，调用方必须停止输出）。 */
    public static boolean isCurrent(long id) {
        return id != 0L && CURRENT.get() == id;
    }

    /** 正常收尾：只有仍是当前请求时才会清空，避免把后来者顶掉。 */
    public static void finish(long id) {
        CURRENT.compareAndSet(id, 0L);
        inFlight = null;
    }

    /** 是否有一个请求正在进行（{@code /ai cancel} 用它判断有没有必要提示）。 */
    public static boolean isBusy() {
        return CURRENT.get() != 0L;
    }

    /**
     * 登记底层 future，让 {@link #cancel()} 能尝试真正中断它。
     * 流式请求没有单一的 future，可以不登记。
     */
    public static void track(CompletableFuture<?> future) {
        inFlight = future;
    }

    /**
     * 取消当前请求。
     *
     * @return true = 确实取消掉了一个进行中的请求；false = 本来就没有请求在跑
     */
    public static boolean cancel() {
        long previous = CURRENT.getAndSet(0L);
        CompletableFuture<?> future = inFlight;
        inFlight = null;

        // 先停掉"思考中"动画：玩家按下取消之后，转圈必须马上消失，
        // 不能等到（可能长达 90 秒的）HTTP 超时。
        Runnable hook = cancelHook;
        cancelHook = null;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // 收尾动作失败不影响取消本身
            }
        }

        if (future != null) {
            try {
                // 真正中断底层连接：这样模型那边也会停止生成，不只是"不看结果"
                future.cancel(true);
            } catch (Throwable ignored) {
                // 取消失败无所谓：回调里的 isCurrent 检查已经能挡住它的输出
            }
        }
        return previous != 0L;
    }
}
