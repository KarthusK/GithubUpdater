package com.githubupdater.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 托盘的回归测试。
 *
 * <p><b>这个测试要守住的核心约束只有一条：托盘菜单里不能出现非 ASCII 字符。</b>
 *
 * <p>背景是一次过度归因的教训。托盘菜单中文显示成方块，根因确实是
 * 「{@code java.awt.PopupMenu} 在 Windows 上交给 Win32 {@code TrackPopupMenu} 渲染，
 * {@code MenuItem.setFont()} 不生效」。但当时的解法——改用 Swing 自绘菜单——
 * 代价过高：无主窗口的 Java 进程拿不到焦点，菜单无法自动关闭，只能靠
 * 80ms 轮询 {@code GetAsyncKeyState} 打补丁，又引出「进程退不掉」「菜单不隐藏」
 * 「退出项失效」三个新 bug。
 *
 * <p><b>真正的解法是把菜单文案换成英文</b>：纯 ASCII 任何字体都能渲染，
 * 乱码问题从根上消失；而原生菜单由 explorer 托管，自动隐藏、Esc 关闭都是免费的。
 *
 * <p>所以这个测试断言的不是"用了什么技术"，而是"菜单文案必须保持 ASCII"——
 * 只要这条成立，方案就是安全的。谁哪天把 "Exit" 改回 "退出"，这里会立刻失败。
 */
class TrayManagerTest {

    /** 托盘菜单允许出现的最大文案长度。菜单只有一项，长文案说明设计走样了。 */
    private static final int MAX_MENU_TEXT_LENGTH = 20;

    @Test
    @DisplayName("菜单文案必须是纯 ASCII —— 这是整个方案成立的前提")
    void menuTextMustBeAscii() throws Exception {
        String code = stripComments(readTrayManagerSource());

        // 找到 new MenuItem("...") 里的字面量，逐个校验
        var pattern = java.util.regex.Pattern.compile(
                "new\\s+MenuItem\\s*\\(\\s*\"([^\"]*)\"");
        var matcher = pattern.matcher(code);

        int found = 0;
        while (matcher.find()) {
            found++;
            String text = matcher.group(1);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int idx = i;
                assertTrue(c >= 0x20 && c <= 0x7E,
                        () -> "菜单文案 \"" + text + "\" 第 " + idx + " 个字符 '"
                                + c + "' 不是 ASCII。\n"
                                + "托盘菜单由 Win32 TrackPopupMenu 渲染，"
                                + "MenuItem.setFont() 不生效，非 ASCII 字符会显示成方块。\n"
                                + "如果确实需要中文，请重新评估整个托盘方案"
                                + "（Swing 自绘的代价见 TrayManager 类注释），"
                                + "不要只改文案。");
            }
        }
        assertTrue(found > 0, "没有找到任何 MenuItem，托盘菜单是不是被删了？");
    }

    @Test
    @DisplayName("菜单项数量必须保持极简（只有 Exit）")
    void menuStaysMinimal() throws Exception {
        String code = stripComments(readTrayManagerSource());
        long count = java.util.regex.Pattern
                .compile("new\\s+MenuItem\\s*\\(")
                .matcher(code).results().count();

        assertTrue(count >= 1, "至少要有一个退出项，否则用户无法从托盘退出");
        assertTrue(count <= 2,
                "菜单项应保持极简（当前设计只有 Exit）。实际 " + count + " 项。\n"
                        + "托盘不是主界面，操作都应在浏览器面板里做；"
                        + "项数变多意味着有人在托盘里堆功能。");
    }

    @Test
    @DisplayName("菜单项文案不能过长")
    void menuTextNotTooLong() throws Exception {
        String code = stripComments(readTrayManagerSource());
        var matcher = java.util.regex.Pattern
                .compile("new\\s+MenuItem\\s*\\(\\s*\"([^\"]*)\"")
                .matcher(code);
        while (matcher.find()) {
            String text = matcher.group(1);
            assertTrue(text.length() <= MAX_MENU_TEXT_LENGTH,
                    "菜单文案 \"" + text + "\" 过长（" + text.length()
                            + " 字符），托盘菜单应保持简短");
        }
    }

    @Test
    @DisplayName("必须用原生 PopupMenu 并交给系统托管，而不是 Swing 自绘")
    void mustUseNativePopupMenu() throws Exception {
        // 只看代码，不看注释。
        // 类注释里会提到 JWindow / MenuDismissMonitor 作为历史教训的说明，
        // 直接 contains() 会被自己的注释误伤。
        String code = stripComments(readTrayManagerSource());

        assertTrue(code.contains("new PopupMenu("),
                "必须构造原生 java.awt.PopupMenu —— 它由 explorer.exe 托管，"
                        + "自动隐藏 / Esc 关闭 / 点外部关闭都是系统免费提供的。");

        assertTrue(code.contains("setPopupMenu"),
                "必须调用 icon.setPopupMenu(menu) 交给系统托管。");

        // Swing 自绘方案已被证明代价过高（见类注释），不允许回归
        assertFalse(code.contains("new JWindow("),
                "不能再用 Swing 的 JWindow 自绘菜单。\n"
                        + "它拿不到焦点，无法自动关闭，只能靠轮询 GetAsyncKeyState 打补丁，"
                        + "而那套补丁引出了进程退不掉、菜单不隐藏、退出项失效三个 bug。");
        assertFalse(code.contains("MenuDismissMonitor"),
                "MenuDismissMonitor 已删除 —— 原生菜单不需要轮询按键状态。");
        assertFalse(code.contains("GetAsyncKeyState"),
                "不应再轮询 GetAsyncKeyState —— 原生菜单的关闭由系统负责。");
    }

    /**
     * 去掉 Java 源码里的注释，只留代码。
     *
     * <p>够用即可：本项目的注释是 {@code //} 与 {@code /* *}{@code /} 两种，
     * 不做完整的词法分析，但会跳过字符串字面量以免误删。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        boolean inChar = false;

        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                i++;
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i += 2;
                    continue;
                }
                if (c == '\n') {
                    out.append(c);   // 保留换行，行号不变
                }
                i++;
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                i++;
                continue;
            }

            if (c == '/' && next == '/') {
                inLine = true;
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @Test
    @DisplayName("四种状态图标都必须能渲染出非空内容")
    void allStateIconsRender() throws Exception {
        Method m = TrayManager.class.getDeclaredMethod("drawIcon", TrayManager.State.class);
        m.setAccessible(true);

        for (TrayManager.State state : TrayManager.State.values()) {
            Image img = (Image) m.invoke(null, state);
            assertNotNull(img, state + " 的图标不能为 null");

            BufferedImage bi = (BufferedImage) img;
            assertEquals(16, bi.getWidth(), state + " 图标宽度应为 16");
            assertEquals(16, bi.getHeight(), state + " 图标高度应为 16");

            // 统计不透明像素，确认真的画了东西而不是全透明
            int opaque = 0;
            for (int y = 0; y < bi.getHeight(); y++) {
                for (int x = 0; x < bi.getWidth(); x++) {
                    if (((bi.getRGB(x, y) >>> 24) & 0xFF) > 0) {
                        opaque++;
                    }
                }
            }
            int finalOpaque = opaque;
            assertTrue(finalOpaque > 100,
                    state + " 的图标只有 " + finalOpaque + " 个不透明像素，"
                            + "看起来没画出来");
        }
    }

    @Test
    @DisplayName("四种状态图标必须互不相同（颜色 + 形状双重编码）")
    void stateIconsAreDistinct() throws Exception {
        Method m = TrayManager.class.getDeclaredMethod("drawIcon", TrayManager.State.class);
        m.setAccessible(true);

        TrayManager.State[] states = TrayManager.State.values();
        String[] signatures = new String[states.length];

        for (int i = 0; i < states.length; i++) {
            BufferedImage bi = (BufferedImage) m.invoke(null, states[i]);
            signatures[i] = pixelSignature(bi);
        }

        for (int i = 0; i < states.length; i++) {
            for (int j = i + 1; j < states.length; j++) {
                int a = i;
                int b = j;
                assertFalse(signatures[i].equals(signatures[j]),
                        () -> states[a] + " 与 " + states[b]
                                + " 的图标完全一样，用户无法区分状态");
            }
        }
    }

    @Test
    @DisplayName("状态切换：进度事件应进入 DOWNLOADING")
    void progressSwitchesToDownloading() throws Exception {
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });
        tray.onDownloadEvent(true, false, false);
        assertEquals(TrayManager.State.DOWNLOADING, readState(tray));
    }

    @Test
    @DisplayName("状态切换：失败优先，不被后续完成覆盖")
    void failureTakesPrecedence() throws Exception {
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });

        tray.onDownloadEvent(false, false, true);
        assertEquals(TrayManager.State.FAILED, readState(tray));

        // 之后有任务完成，但失败标记还在——不能把 FAILED 覆盖掉，
        // 否则用户会错过"有东西失败了"这个信息
        tray.onDownloadEvent(false, true, false);
        assertEquals(TrayManager.State.FAILED, readState(tray),
                "有失败残留时，完成事件不应覆盖 FAILED 状态");
    }

    @Test
    @DisplayName("状态切换：无失败时完成应进入 DONE")
    void doneWhenNoFailure() throws Exception {
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });
        tray.onDownloadEvent(false, true, false);
        assertEquals(TrayManager.State.DONE, readState(tray));
    }

    @Test
    @DisplayName("状态切换：开始新下载时清掉失败标记")
    void newDownloadClearsFailure() throws Exception {
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });

        tray.onDownloadEvent(false, false, true);
        assertEquals(TrayManager.State.FAILED, readState(tray));

        // 新一轮下载开始，失败标记应被清掉
        tray.onDownloadEvent(true, false, false);
        assertEquals(TrayManager.State.DOWNLOADING, readState(tray));

        // 完成后能正常进入 DONE
        tray.onDownloadEvent(false, true, false);
        assertEquals(TrayManager.State.DONE, readState(tray),
                "新一轮下载成功后应能进入 DONE，说明失败标记已被清除");
    }

    @Test
    @DisplayName("暂停/取消后回到空闲，但不覆盖失败状态")
    void idleRespectsFailure() throws Exception {
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });

        tray.onDownloadEvent(true, false, false);
        tray.onIdle();
        assertEquals(TrayManager.State.IDLE, readState(tray));

        tray.onDownloadEvent(false, false, true);
        tray.onIdle();
        assertEquals(TrayManager.State.FAILED, readState(tray),
                "有失败残留时，回到空闲不应覆盖 FAILED");
    }

    @Test
    @DisplayName("未安装托盘时切换状态不能抛异常")
    void stateChangeWithoutTrayDoesNotThrow() {
        // 构造但不 install()，模拟托盘不可用的环境（如 Windows Server Core）
        TrayManager tray = new TrayManager("http://127.0.0.1:1/", () -> { });

        tray.onDownloadEvent(true, false, false);
        tray.onDownloadEvent(false, false, true);
        tray.onDownloadEvent(false, true, false);
        tray.onIdle();
        tray.uninstall();   // 没安装过就卸载，也必须是安全的 no-op
    }

    /** 读 TrayManager 的源码。测试运行时 cwd 是项目根。 */
    private static String readTrayManagerSource() throws Exception {
        Path p = Path.of("src/main/java/com/githubupdater/transport/TrayManager.java");
        assertTrue(Files.exists(p), "找不到 TrayManager 源码：" + p.toAbsolutePath());
        return Files.readString(p);
    }

    /** 反射读私有字段 currentState。 */
    private static TrayManager.State readState(TrayManager tray) throws Exception {
        Field f = TrayManager.class.getDeclaredField("currentState");
        f.setAccessible(true);
        return (TrayManager.State) f.get(tray);
    }

    /**
     * 把图标的所有像素压成一个签名串，用于比较两个图标是否相同。
     *
     * <p>直接比 {@code BufferedImage.equals} 在 AWT 里是按引用比，不可靠。
     */
    private static String pixelSignature(BufferedImage img) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                sb.append(Integer.toHexString(img.getRGB(x, y)));
            }
        }
        return sb.toString();
    }
}
