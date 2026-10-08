package com.example.mcai.util;

/**
 * 把模型返回的思维链整理成可以直接丢进聊天栏的一行文本。
 *
 * <p>为什么要截断：实测同一个问题，{@code deepseek-flash} 返回约 4100 字、
 * {@code deepseek-v4-pro} 约 6600 字的思维链。Minecraft 聊天栏会把长文本折行，
 * 4000 字大约是 40 多行，直接灌进去会把聊天记录整个刷掉。
 *
 * <p>所以默认只显示前 {@code reasoning_max_chars} 个字（默认 500，可在 mcai.json 里改），
 * 并标注完整长度；把 {@code reasoning_max_chars} 设为 {@code 0} 即可显示完整思考过程。
 */
public final class ReasoningFormatter {

    /** 前缀，浅灰 [思考] + 深灰正文，视觉上和白色答案区分开。 */
    private static final String PREFIX = "§7[思考] §8";

    private ReasoningFormatter() {}

    /**
     * @param reasoning 原始思维链（可含换行）
     * @param maxChars  最多显示多少字；{@code <= 0} 表示不截断
     * @return 可直接发送的文本；没有思考内容时返回 {@code null}
     */
    public static String format(String reasoning, int maxChars) {
        if (reasoning == null || reasoning.isBlank()) {
            return null;
        }

        // 聊天栏是单行渲染，换行会被吃掉，这里统一压成空格；顺便合并连续空白
        String text = reasoning.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();

        if (text.isEmpty()) {
            return null;
        }

        if (maxChars > 0 && text.length() > maxChars) {
            return PREFIX + text.substring(0, maxChars)
                    + " §8…（省略 " + (text.length() - maxChars) + " 字，共 " + text.length()
                    + " 字；把 mcai.json 的 reasoning_max_chars 设为 0 可显示完整思考）";
        }
        return PREFIX + text;
    }
}
