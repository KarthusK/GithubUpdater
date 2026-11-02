package com.githubupdater.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 连接管理与广播。
 *
 * <p>为什么用 SSE 而不是 WebSocket（见 docs/03-API接口.md）：
 * 进度推送是单向的，{@code EventSource} 语义更简单；命令仍走普通 REST。
 *
 * <p>但本项目的实现不走 {@code EventSource}——它重连时不带 {@code Authorization} 头。
 * 前端用 {@code fetch} + {@code ReadableStream} 自己解析，服务端仍是标准 SSE 格式。
 *
 * <p><b>连接生命周期由调用方的 {@code ctx.async()} 维持</b>。
 * 详见 {@code WebServer#events} 的注释——Javalin 在 handler 返回时会无条件
 * close + complete，所以必须把响应写入交给异步任务，且由本类负责实际写流。
 *
 * <p>用 {@link CopyOnWriteArrayList} 是因为遍历远多于增删，且遍历时不能因为
 * 并发修改抛 {@code ConcurrentModificationException}。
 */
public final class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    /** 心跳间隔，防止中间层因空闲断开。同时也是"发现死连接"的周期。 */
    private static final long KEEPALIVE_INTERVAL_MS = 15_000;

    /** 最大并发连接数。单用户桌面工具，超过这个数一定是出了问题。 */
    private static final int MAX_CLIENTS = 16;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private Thread keepaliveThread;
    private volatile boolean running = false;

    public void start() {
        running = true;
        keepaliveThread = new Thread(this::keepaliveLoop, "sse-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }

    /**
     * 注册一个新连接。
     *
     * @param out     响应输出流
     * @param onClose 连接结束时回调（用于 {@code AsyncContext.complete()}）
     * @return 客户端句柄；已达上限时返回 null
     */
    public Client register(OutputStream out, Runnable onClose) {
        if (clients.size() >= MAX_CLIENTS) {
            log.warn("SSE 连接数已达上限 {}，拒绝新连接", MAX_CLIENTS);
            return null;
        }
        Client c = new Client(out, onClose);
        clients.add(c);
        log.debug("SSE 客户端接入，当前 {} 个", clients.size());
        return c;
    }

    public void unregister(Client client) {
        if (client != null) {
            clients.remove(client);
            log.debug("SSE 客户端断开，当前 {} 个", clients.size());
        }
    }

    public int clientCount() {
        return clients.size();
    }

    /**
     * 广播事件。
     *
     * <p>发送失败的客户端会被移除——它已经断开，留着只会反复失败。
     */
    public void broadcast(String event, Object data) {
        if (clients.isEmpty()) {
            return;
        }
        String frame;
        try {
            frame = "event: " + event + "\ndata: " + mapper.writeValueAsString(data) + "\n\n";
        } catch (IOException e) {
            log.warn("序列化 SSE 事件失败: {}", e.getMessage());
            return;
        }
        sendToAll(frame.getBytes(StandardCharsets.UTF_8));
    }

    private void sendToAll(byte[] bytes) {
        for (Client c : clients) {
            if (!c.send(bytes)) {
                clients.remove(c);
                c.close();
            }
        }
    }

    private void keepaliveLoop() {
        while (running) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
                if (clients.isEmpty()) {
                    continue;
                }
                // SSE 注释行，客户端会忽略但能保持连接，同时探测死连接
                sendToAll(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop() {
        running = false;
        if (keepaliveThread != null) {
            keepaliveThread.interrupt();
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
    }

    /** 单个 SSE 连接。 */
    public static final class Client {

        /** 用于保证 onClose 只被调用一次。 */
        private final AtomicInteger closedFlag = new AtomicInteger(0);

        private final OutputStream out;
        private final Runnable onClose;
        private volatile boolean closed = false;

        Client(OutputStream out, Runnable onClose) {
            this.out = out;
            this.onClose = onClose;
        }

        /** 连接是否已断开（发送失败或主动关闭）。 */
        public boolean isClosed() {
            return closed;
        }

        /** 发送一帧。失败返回 false，调用方应移除该客户端。 */
        boolean send(byte[] bytes) {
            if (closed) {
                return false;
            }
            try {
                out.write(bytes);
                out.flush();
                return true;
            } catch (IOException e) {
                closed = true;
                return false;
            }
        }

        /** 发送初始 snapshot。 */
        public void sendSnapshot(Map<String, Object> snapshot) {
            try {
                String frame = "event: snapshot\ndata: "
                        + new ObjectMapper().writeValueAsString(snapshot) + "\n\n";
                send(frame.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("发送 snapshot 失败: {}", e.getMessage());
            }
        }

        /**
         * 关闭连接。幂等：重复调用只会触发一次 {@code onClose}。
         */
        public void close() {
            if (!closedFlag.compareAndSet(0, 1)) {
                return;
            }
            closed = true;
            try {
                out.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
            if (onClose != null) {
                try {
                    onClose.run();
                } catch (RuntimeException e) {
                    // AsyncContext 可能已被容器完成，重复 complete 会抛 IllegalStateException
                    log.debug("SSE 连接收尾回调失败（通常可忽略）: {}", e.getMessage());
                }
            }
        }
    }
}
