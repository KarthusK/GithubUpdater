package com.githubupdater.infra.fs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件替换结果。
 *
 * <p>三态：成功 / 被占用 / 失败。被占用不是失败——它需要用户决策
 * （结束进程或稍后），必须与"替换出错"区分开。
 */
public sealed interface ReplaceResult
        permits ReplaceResult.Success, ReplaceResult.Locked, ReplaceResult.Failed {

    /** 替换成功。 */
    record Success(Path target, Path backup, long backupBytes) implements ReplaceResult {
    }

    /**
     * 目标文件被占用，未执行替换。
     *
     * @param processes 占用进程
     */
    record Locked(List<ProcessInfo> processes) implements ReplaceResult {
    }

    /**
     * 替换失败。
     *
     * @param rolledBack 是否已成功回滚（true 表示目标文件仍是旧的完好版本）
     */
    record Failed(String message, Throwable cause, boolean rolledBack) implements ReplaceResult {
    }

    /** 占用进程信息。 */
    record ProcessInfo(int pid, String name) {

        public String describe() {
            return name + " (PID " + pid + ")";
        }
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isLocked() {
        return this instanceof Locked;
    }

    default boolean isFailed() {
        return this instanceof Failed;
    }
}
