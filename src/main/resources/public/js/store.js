// @ts-check
/**
 * 全局状态（单一数据源）。
 *
 * 全部真相在 daemon 侧，前端只持有"从服务端拉取的快照 + SSE 增量"。
 * 所以刷新页面零恢复成本——重新拉一遍就完全一致。
 *
 * 进度不走这里，见 progress.js。那是高频局部更新，走全局广播会拖垮列表。
 */

export const store = {
    /** @type {Array<any>} 条目列表 */
    entries: [],
    /** @type {Map<number, any>} taskId → 任务 */
    tasks: new Map(),
    /** @type {any} {remaining, limit, authenticated, secondsUntilReset} */
    quota: null,
    /** 是否有检查在跑 */
    checksInProgress: false,
    /** SSE 连接状态：connecting | open | closed | error */
    connectionStatus: 'connecting',
    /** @type {any} 最近一次错误，用于顶部提示 */
    lastError: null,

    /** 界面状态（纯前端，不来自服务端） */
    ui: {
        /** list | detail | settings */
        view: 'list',
        /** @type {number|null} */
        selectedEntryId: null,
        /** 向导是否打开 */
        wizardOpen: false,
    },

    /** @type {Set<Function>} */
    listeners: new Set(),

    /**
     * 订阅变更。
     * @param {Function} fn
     * @returns {Function} 取消订阅
     */
    subscribe(fn) {
        this.listeners.add(fn);
        return () => this.listeners.delete(fn);
    },

    /** 通知所有订阅者。高频路径不要调这个。 */
    emit() {
        for (const fn of this.listeners) {
            try {
                fn();
            } catch (e) {
                console.error('store 订阅者抛错', e);
            }
        }
    },

    // ── 变更方法（统一在改动后 emit） ──────────────────

    /** @param {Array<any>} entries */
    setEntries(entries) {
        this.entries = entries;
        this.emit();
    },

    /** @param {Array<any>} tasks */
    setTasks(tasks) {
        this.tasks = new Map(tasks.map(t => [t.id, t]));
        this.emit();
    },

    /** @param {any} task */
    upsertTask(task) {
        const prev = this.tasks.get(task.id);
        // 保留已有的 running 标记（SSE 的 state 事件不带它）
        this.tasks.set(task.id, { ...prev, ...task });
        this.emit();
    },

    /**
     * 只更新任务状态，不整体重建。
     * @param {number} taskId
     * @param {string} state
     */
    setTaskState(taskId, state) {
        const t = this.tasks.get(taskId);
        if (!t) return;
        t.state = state;
        if (state === 'RUNNING') t.running = true;
        if (state === 'DONE' || state === 'FAILED' || state === 'CANCELLED' || state === 'PAUSED') {
            t.running = false;
        }
        this.emit();
    },

    /** @param {number} entryId @param {any} patch */
    patchEntry(entryId, patch) {
        const e = this.entries.find(x => x.id === entryId);
        if (!e) return;
        Object.assign(e, patch);
        this.emit();
    },

    /** @param {any} quota */
    setQuota(quota) {
        this.quota = quota;
        this.emit();
    },

    /** @param {boolean} running */
    setChecksInProgress(running) {
        this.checksInProgress = running;
        this.emit();
    },

    /** @param {string} status */
    setConnectionStatus(status) {
        if (this.connectionStatus === status) return;
        this.connectionStatus = status;
        this.emit();
    },

    /** @param {any} err */
    setError(err) {
        this.lastError = err;
        this.emit();
    },

    // ── 界面状态 ──────────────────────────────────────

    /** @param {string} view @param {number|null} [entryId] */
    navigate(view, entryId = null) {
        this.ui.view = view;
        this.ui.selectedEntryId = entryId;
        this.emit();
    },

    /** @param {boolean} open */
    setWizardOpen(open) {
        this.ui.wizardOpen = open;
        this.emit();
    },
};

/** 供组件使用的稳定 selector（不要在渲染中内联定义，会导致反复重订阅）。 */
export const selectors = {
    entries: s => s.entries,
    tasks: s => s.tasks,
    quota: s => s.quota,
    connectionStatus: s => s.connectionStatus,
    checksInProgress: s => s.checksInProgress,
    view: s => s.ui.view,
    selectedEntryId: s => s.ui.selectedEntryId,
    wizardOpen: s => s.ui.wizardOpen,
    lastError: s => s.lastError,
};
