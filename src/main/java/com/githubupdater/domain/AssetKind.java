package com.githubupdater.domain;

/**
 * 资产处理类型。
 *
 * <p>四类资产共用"条目 + 资产规则"模型，只是 {@code kind} 不同，
 * 不要为它们写四套并行代码。见 docs/01-架构设计.md。
 */
public enum AssetKind {

    /** 单文件替换：备份 → 原子替换，可回滚。 */
    BINARY,

    /** 压缩包解压：解压到暂存 → 校验 → 覆盖。 */
    ARCHIVE,

    /** 源码更新：git clone / fetch / checkout 到 tag。 */
    SOURCE,

    /** 归档收藏：只落盘，没有"应用"动作（如 APK 存档）。 */
    COLLECTION;

    /** 是否有"应用"这个动作。COLLECTION 的终点就是文件落盘。 */
    public boolean hasApplyAction() {
        return this != COLLECTION;
    }

    public static AssetKind parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 kind: " + raw);
        }
    }
}
