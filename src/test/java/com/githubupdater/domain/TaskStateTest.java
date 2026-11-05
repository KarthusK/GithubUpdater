package com.githubupdater.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务状态的槽位占用语义。
 *
 * <p>这组断言锁住"同一条规则不能有两个任务同时写同一个 .part"的前提。
 * 判错了会有两种后果，都很糟：
 * <ul>
 *   <li>该占的没占 → 两个任务撞文件，第二个报"另一个程序正在使用此文件"</li>
 *   <li>不该占的占了 → 用户下载完成后想重新下一次却被拒绝</li>
 * </ul>
 */
class TaskStateTest {

    @Test
    @DisplayName("进行中的四种状态占着 .part")
    void activeStatesHoldSlot() {
        assertTrue(TaskState.QUEUED.holdsDownloadSlot());
        assertTrue(TaskState.RUNNING.holdsDownloadSlot());
        assertTrue(TaskState.VERIFYING.holdsDownloadSlot());
        assertTrue(TaskState.PAUSED.holdsDownloadSlot());
    }

    @Test
    @DisplayName("DONE 不占用——.part 已改名成 .ready，重新下载是合理的")
    void doneDoesNotHoldSlot() {
        // 用户下载完之后往往想重新下一次（覆盖或换版本）。
        // 若把 DONE 也算占用，这个再正常不过的操作会被拒绝。
        assertFalse(TaskState.DONE.holdsDownloadSlot());
    }

    @Test
    @DisplayName("失败与取消不占用——.part 已被清理")
    void terminalStatesDoNotHoldSlot() {
        assertFalse(TaskState.FAILED.holdsDownloadSlot());
        assertFalse(TaskState.CANCELLED.holdsDownloadSlot());
        assertFalse(TaskState.APPLIED.holdsDownloadSlot());
    }

    @Test
    @DisplayName("每个状态都必须被显式归类，不能漏")
    void everyStateIsClassified() {
        // 新增状态时如果忘记考虑这条，这个测试正好提醒。
        for (TaskState s : TaskState.values()) {
            boolean holds = s.holdsDownloadSlot();
            boolean terminal = s.isTerminal();
            if (holds && terminal) {
                throw new AssertionError(s + " 既是终态又占着下载槽位，语义矛盾");
            }
        }
    }
}
