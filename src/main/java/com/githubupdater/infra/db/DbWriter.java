package com.githubupdater.infra.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库单写线程。
 *
 * <p><b>为什么必须串行</b>：SQLite 是单写者模型。多线程直接写会频繁遇到
 * {@code SQLITE_BUSY}，{@code busy_timeout} 只能缓解不能消除。串行化的代码更简单，
 * 且本场景的写入量完全在单线程能力范围内。
 *
 * <p>所有写操作通过 {@link #submit(Runnable)} 投递，读操作可以在其他线程并发进行
 * （WAL 模式下读写不阻塞）。
 */
public final class DbWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbWriter.class);

    /** 队列容量上限。满了说明写入速度跟不上，宁可阻塞调用方也不要无声丢弃。 */
    private static final int QUEUE_CAPACITY = 1024;

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    public DbWriter() {
        this.thread = new Thread(this::loop, "db-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // 单条写失败不能让整个写线程死掉
                    log.error("数据库写操作失败", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 投递一个写操作。调用方会被阻塞直到入队（队列满时），保证不丢写。
     *
     * <p>注意：这是异步的，方法返回不代表写已完成。需要确认结果时，
     * 在 {@code task} 内部持有 CompletableFuture 并由调用方 await。
     */
    public void submit(Runnable task) {
        if (!running.get()) {
            throw new IllegalStateException("DbWriter 已关闭");
        }
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("投递写任务被中断", e);
        }
    }

    /**
     * 投递写操作并<b>等待结果</b>。
     *
     * <p>用于"插入后立刻需要自增 ID"这类场景。异步 submit 做不到这一点：
     * 任务还没执行，调用方就已经读走了结果变量。
     *
     * <p>不要在 db-writer 线程内部调用本方法，否则会自我等待死锁。
     */
    public <T> T submitAndGet(java.util.function.Supplier<T> task) {
        if (Thread.currentThread().getName().equals("db-writer")) {
            throw new IllegalStateException("不能在 db-writer 线程内调用 submitAndGet，会死锁");
        }
        if (!running.get()) {
            throw new IllegalStateException("DbWriter 已关闭");
        }

        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        try {
            queue.put(() -> {
                try {
                    future.complete(task.get());
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待写任务结果被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("写任务执行失败", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("写任务超时（30 秒）", e);
        }
    }

    /** 当前排队的写任务数，用于诊断。 */
    public int pendingWrites() {
        return queue.size();
    }

    @Override
    public void close() {
        running.set(false);
        try {
            thread.join(5000);
            if (thread.isAlive()) {
                log.warn("db-writer 未在 5 秒内结束，仍有 {} 条待写", queue.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
