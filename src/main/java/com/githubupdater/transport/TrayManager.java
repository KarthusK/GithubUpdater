package com.githubupdater.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

// 注意：不要 import java.awt.TrayIcon。
// 这个类曾经叫 TrayIcon，import 之后 LoggerFactory.getLogger(TrayIcon.class)
// 会静默绑定到 java.awt.TrayIcon，日志全部打到错误的 logger 名下。
// 需要 AWT 的托盘图标时一律写全限定名 java.awt.TrayIcon。

/**
 * 系统托盘。
 *
 * <h2>交互模型（刻意做到最简）</h2>
 *
 * <ul>
 *   <li><b>双击图标</b> → 打开浏览器面板（AWT 在 Windows 上只在双击时触发
 *       {@code ActionListener}，单击不触发，正好是想要的行为）</li>
 *   <li><b>右键</b> → 只有一个 "Exit" 项</li>
 * </ul>
 *
 * <p>界面本来就在浏览器里，关掉浏览器就等于释放了全部界面内存（ADR-001），
 * 所以托盘不需要「最小化到托盘」这种概念，也不需要承载业务操作。
 *
 * <h2>为什么菜单只有一项、而且是英文</h2>
 *
 * <p>这是本项目踩得最隐蔽的一个坑，也是<b>一次过度归因的教训</b>。
 *
 * <p>托盘菜单里的中文一度全部显示成方块。当时的诊断是：{@code java.awt.PopupMenu}
 * 在 Windows 上把菜单交给 Win32 的 {@code TrackPopupMenu} 原生 API 渲染，
 * {@code MenuItem.setFont()} 只是被 Java 对象记录下来，对实际渲染毫无影响。
 * 这个结论<b>是对的</b>。
 *
 * <p>但由此推出的解法——「改用 Swing {@code JWindow} 自己画菜单」——<b>代价过高</b>：
 * 无主窗口的 Java 进程拿不到焦点，{@code JPopupMenu} 的「点外面自动关闭」机制完全失效，
 * 只能靠 {@code MenuDismissMonitor} 每 80ms 轮询 {@code GetAsyncKeyState} 补上。
 * 这套补丁又引出了三个新 bug：退出后进程不退出（EDT 死锁）、菜单不自动隐藏、
 * 退出菜单项失效（监控器把点击菜单项也当成"点外面"）。
 *
 * <p><b>真正的解法是降低需求，而不是提高技术复杂度</b>：
 * 把菜单项从中文换成英文，问题就从「如何让 Java 正确渲染中文菜单」
 * 降级为「根本不需要渲染中文」——{@code "Exit"} 是纯 ASCII，
 * 任何系统字体都能画，<b>不存在乱码的可能</b>。
 *
 * <p>而原生菜单由 explorer.exe 托管，<b>自动隐藏、Esc 关闭、点击外部关闭
 * 全部是系统免费提供的</b>，不需要一行代码。{@code MenuDismissMonitor}
 * 因此被整个删除（连同它的 200 行时序补丁）。
 *
 * <p>教训：当为了让某个技术方案可用而不断打补丁时，
 * 应该回头质疑方案本身的前提是否必要。
 *
 * <h2>状态图标</h2>
 *
 * <p>托盘图标承载四种状态（空闲 / 下载中 / 完成 / 失败），用
 * {@link java.awt.TrayIcon#setImage(Image)} 动态切换。图标全部预渲染并缓存，
 * 切换时只是换引用，不会重复绘制。
 *
 * <p>颜色与形状<b>双重编码</b>（蓝点 / 下箭头 / 对勾 / 叉），
 * 色盲用户也能靠形状分辨，不单靠颜色。
 */
public final class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    /** 图标边长。托盘标准尺寸是 16×16。 */
    private static final int ICON_SIZE = 16;

    /** 托盘状态。 */
    public enum State {
        /** 空闲：没有任务在跑。 */
        IDLE,
        /** 下载中（含校验）。 */
        DOWNLOADING,
        /** 最近一批任务全部成功完成。 */
        DONE,
        /** 最近有任务失败。 */
        FAILED
    }

    private final String url;
    private final Runnable onExit;

    private TrayHandle handle;

    /** 预渲染的状态图标，构造时一次性生成。 */
    private final Map<State, Image> icons = new EnumMap<>(State.class);

    /** 当前状态，避免同一状态重复 setImage。 */
    private volatile State currentState = State.IDLE;

    /** 当前有任务在下载（用于"最后一个任务完成后才转 DONE"）。 */
    private final java.util.concurrent.atomic.AtomicInteger activeTasks =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 当前是否有失败任务未清除。 */
    private volatile boolean hasFailure = false;

    public TrayManager(String url, Runnable onExit) {
        this.url = url;
        this.onExit = onExit;
        for (State s : State.values()) {
            icons.put(s, drawIcon(s));
        }
    }

    /** 托盘句柄，用于卸载。 */
    private record TrayHandle(SystemTray tray, java.awt.TrayIcon icon) {
    }

    /**
     * 安装托盘图标。
     *
     * @return 是否安装成功
     */
    public boolean install() {
        if (!SystemTray.isSupported()) {
            log.warn("当前系统不支持托盘（可能是精简版 Windows 或远程会话），"
                    + "请从日志或 data/runtime.json 获取面板地址：{}", url);
            return false;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            // 关键：不传 PopupMenu 给构造函数，改用 setPopupMenu 显式设置。
            // 菜单由 explorer.exe 托管：自动隐藏、Esc 关闭、点外部关闭都是免费的。
            java.awt.TrayIcon icon = new java.awt.TrayIcon(
                    icons.get(State.IDLE), "GithubUpdater — 双击打开面板");
            icon.setImageAutoSize(true);

            PopupMenu menu = new PopupMenu();
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                log.info("用户从托盘退出");
                if (onExit != null) {
                    onExit.run();
                }
            });
            menu.add(exitItem);
            icon.setPopupMenu(menu);

            // 双击 = 打开面板。
            // AWT 在 Windows 上只在双击时触发 ActionListener，单击不触发，
            // 所以不需要自己判断 clickCount。
            icon.addActionListener(e -> openPanel());

            tray.add(icon);
            this.handle = new TrayHandle(tray, icon);

            // 首次安装给一个气泡提示，让用户知道程序已经在跑。
            // 内容是中文——气泡不是菜单，由 Java 自己绘制，中文正常。
            icon.displayMessage("GithubUpdater 已在后台运行",
                    "双击图标打开面板，右键可退出。",
                    java.awt.TrayIcon.MessageType.INFO);

            log.info("托盘图标已安装（原生菜单，仅 Exit 项；双击打开面板）");
            return true;

        } catch (AWTException | RuntimeException e) {
            log.warn("安装托盘图标失败：{}（面板地址仍是 {}）", e.getMessage(), url);
            return false;
        }
    }

    /**
     * 根据下载事件更新托盘状态。
     *
     * <p>由 {@code Main} 注册到 {@code DownloadService} 的进度监听上。
     * 这个方法会在下载工作线程上被调用，内部只做状态计算与
     * {@code setImage}（AWT 的 TrayIcon 方法本身是线程安全的）。
     *
     * <p>状态转移规则：
     * <ul>
     *   <li>有任务开始下载 → DOWNLOADING，并清掉失败标记</li>
     *   <li>任务失败 → FAILED（失败优先，不会被后续完成覆盖）</li>
     *   <li>全部任务结束且无失败 → DONE</li>
     * </ul>
     */
    public void onDownloadEvent(boolean isProgress, boolean isDone, boolean isFailed) {
        if (isProgress) {
            if (activeTasks.get() == 0) {
                hasFailure = false;
            }
            applyState(State.DOWNLOADING);
            return;
        }

        if (isFailed) {
            hasFailure = true;
            applyState(State.FAILED);
            return;
        }

        if (isDone) {
            // 有失败残留时不覆盖 FAILED——用户更需要知道"有东西失败了"
            if (!hasFailure) {
                applyState(State.DONE);
            }
        }
    }

    /** 回到空闲态。任务被暂停/取消后调用。 */
    public void onIdle() {
        if (!hasFailure) {
            applyState(State.IDLE);
        }
    }

    /** 切换图标。同一状态不重复设置。 */
    private void applyState(State state) {
        if (currentState == state) {
            return;
        }
        currentState = state;

        TrayHandle h = handle;
        if (h == null) {
            return;
        }
        try {
            h.icon().setImage(icons.get(state));
            h.icon().setToolTip(tooltipFor(state));
        } catch (RuntimeException e) {
            log.debug("切换托盘图标失败：{}", e.getMessage());
        }
    }

    private static String tooltipFor(State state) {
        return switch (state) {
            case IDLE -> "GithubUpdater — 空闲";
            case DOWNLOADING -> "GithubUpdater — 下载中";
            case DONE -> "GithubUpdater — 下载完成";
            case FAILED -> "GithubUpdater — 有任务失败";
        };
    }

    /**
     * 卸载托盘图标。
     *
     * <p>{@link SystemTray#remove} 必须在 AWT EDT 上调用——从其它线程调在部分
     * JDK/Windows 组合上会卡住。这里内部切到 EDT，调用方不必关心线程。
     *
     * <p>如果当前已经是 EDT，直接执行（避免 invokeAndWait 自等待死锁）；
     * 否则用带超时的 {@code FutureTask}。超时是必须的：退出流程里调用它，
     * 不能让 EDT 的忙碌拖住整个退出——这正是之前"进程退不掉"的同一类问题。
     */
    public void uninstall() {
        TrayHandle h = handle;
        if (h == null) {
            return;
        }
        handle = null;

        Runnable remove = () -> {
            try {
                h.tray().remove(h.icon());
            } catch (RuntimeException e) {
                log.debug("移除托盘图标失败：{}", e.getMessage());
            }
        };

        if (java.awt.EventQueue.isDispatchThread()) {
            remove.run();
            return;
        }
        java.util.concurrent.FutureTask<Void> task =
                new java.util.concurrent.FutureTask<>(() -> {
                    remove.run();
                    return null;
                });
        java.awt.EventQueue.invokeLater(task);
        try {
            task.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("移除托盘图标超时，跳过（不影响退出）");
            task.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("卸载托盘被中断");
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            log.debug("卸载托盘时出错：{}", e.getMessage());
        }
    }

    /**
     * 在浏览器里打开面板。
     *
     * <p>公开是因为 {@code Main} 在非静默启动时也要调用它
     * （用户双击启动后自动弹面板，而开机自启带 {@code --minimized} 则不弹）。
     */
    public void openPanel() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            // 没有默认浏览器时退化为调用 rundll32
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            log.warn("打开浏览器失败：{}，请手动访问 {}", e.getMessage(), url);
        }
    }

    /**
     * 画状态图标。
     *
     * <p>不用资源文件：省一次 classloader 查找，也省掉"资源没打进 jar"这类问题。
     *
     * <p>四种状态用<b>颜色 + 形状双重编码</b>，不单靠颜色区分：
     * <ul>
     *   <li>IDLE — 蓝色圆角块 + 白色圆点</li>
     *   <li>DOWNLOADING — 亮蓝圆角块 + 白色下箭头</li>
     *   <li>DONE — 绿色圆角块 + 白色对勾</li>
     *   <li>FAILED — 红色圆角块 + 白色叉</li>
     * </ul>
     */
    private static Image drawIcon(State state) {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(switch (state) {
                case IDLE -> new Color(0x4a, 0x9e, 0xff);
                case DOWNLOADING -> new Color(0x30, 0xb0, 0xf0);
                case DONE -> new Color(0x3c, 0xc0, 0x60);
                case FAILED -> new Color(0xe0, 0x50, 0x50);
            });
            g.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 4, 4);

            g.setColor(Color.WHITE);
            int cx = ICON_SIZE / 2;
            switch (state) {
                case IDLE -> g.fillOval(cx - 3, cx - 3, 6, 6);
                case DOWNLOADING -> {
                    g.fillRect(cx - 1, 3, 2, 6);
                    g.fillPolygon(new int[]{cx - 4, cx + 4, cx}, new int[]{8, 8, 13}, 3);
                }
                case DONE -> {
                    g.setStroke(new BasicStroke(2f));
                    g.drawLine(3, 8, 7, 12);
                    g.drawLine(7, 12, 13, 4);
                }
                case FAILED -> {
                    g.setStroke(new BasicStroke(2f));
                    g.drawLine(4, 4, 12, 12);
                    g.drawLine(12, 4, 4, 12);
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }
}
