// @ts-check
/**
 * 全局状态（单一数据源）。
 *
 * 全部真相在 daemon 侧，前端只持有"从服务端拉取的快照 + SSE 增量"。
 * 所以刷新页面零恢复成本——重新拉一遍就完全一致。
 *
 * 进度不走这里，见 progress.js。那是高频局部更新，走全局广播会拖垮列表。
 */

import { getThemePref } from './theme.js';

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

    /**
     * 界面状态（纯前端，不来自服务端）。
     *
     * 与旧版的区别：不再是「list / detail / settings 三个互斥页面」，
     * 而是「左栏常驻条目 + 右栏切换内容」。所以 view 只有两档，
     * 且 detail 是通过 selectedEntryId 是否为空来表达的。
     */
    ui: {
        /** entries | settings */
        view: 'entries',
        /** @type {number|null} */
        selectedEntryId: null,
        /** 向导是否打开 */
        wizardOpen: false,
        /** 左栏过滤串（纯前端，不持久化） */
        filter: '',
        /** 主题偏好：system | light | dark */
        theme: getThemePref(),
    },

    /** @type {Set<Function>} */
    listeners: new Set(),

    /**
     * 正在补拉的任务 id。
     *
     * <p>防止同一条任务被并发拉多次——SSE 重连时 snapshot 之后可能连续
     * 收到几条指向未知任务的 state 事件，不去重会打出一串重复请求。
     * @type {Set<number>}
     */
    _fetchingTasks: new Set(),

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
     *
     * <p><b>任务不在本地时不能静默返回。</b>那会让界面永远不更新——
     * 典型场景：另一个标签页建的任务、`task.created` 在重连时丢包、
     * 或者页面刚好在 SSE 断开的间隙里。改为补拉一次完整任务，
     * 补上之后再走正常路径。
     *
     * @param {number} taskId
     * @param {string} state
     */
    setTaskState(taskId, state) {
        const t = this.tasks.get(taskId);
        if (!t) {
            // 异步补拉；失败静默（任务可能已被清理或本来就是别人的）
            this.fetchTask(taskId);
            return;
        }
        t.state = state;
        if (state === 'RUNNING') t.running = true;
        if (state === 'DONE' || state === 'FAILED' || state === 'CANCELLED' || state === 'PAUSED') {
            t.running = false;
        }
        this.emit();
    },

    /**
     * 从服务端拉一个任务的完整信息并合并进来。
     *
     * <p>放在 store 而不是各调用方各写一份：`useSse` 与 `setTaskState`
     * 都需要它，两处实现迟早会分叉。
     *
     * @param {number} taskId
     */
    async fetchTask(taskId) {
        // 避免同一条任务被并发拉多次（SSE 重连时可能连续触发）
        if (this._fetchingTasks.has(taskId)) return;
        this._fetchingTasks.add(taskId);
        try {
            const { request } = await import('./api.js');
            const task = await request(`/api/tasks/${taskId}`);
            if (task && task.id) {
                this.upsertTask(task);
            }
        } catch (e) {
            // 任务可能已被清理，或服务刚重启——都不该影响界面
        } finally {
            this._fetchingTasks.delete(taskId);
        }
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

    /**
     * 在右栏打开某条目的详情。
     * 找不到该条目时（例如刚被删除）退回条目总览。
     * @param {number|null} entryId
     */
    openEntry(entryId) {
        this.ui.view = 'entries';
        this.ui.selectedEntryId = entryId;
        this.emit();
    },

    /** 关闭详情，右栏回到总览。左栏选中态同时清掉。 */
    closeEntry() {
        this.ui.view = 'entries';
        this.ui.selectedEntryId = null;
        this.emit();
    },

    /** 右栏切到设置。左栏保持原样，选中态保留。 */
    openSettings() {
        this.ui.view = 'settings';
        this.emit();
    },

    /** 左下角「返回」用：先回列表，再决定是否清选中。 */
    backToEntries() {
        this.ui.view = 'entries';
        this.emit();
    },

    /** @param {boolean} open */
    setWizardOpen(open) {
        this.ui.wizardOpen = open;
        this.emit();
    },

    /** @param {string} text 左栏过滤串 */
    setFilter(text) {
        if (this.ui.filter === text) return;
        this.ui.filter = text;
        this.emit();
    },

    /** @param {string} pref system | light | dark */
    setThemePref(pref) {
        if (this.ui.theme === pref) return;
        this.ui.theme = pref;
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
    filter: s => s.ui.filter,
    theme: s => s.ui.theme,
    lastError: s => s.lastError,
};

/**
 * 按过滤串筛条目。
 *
 * 纯前端过滤：条目上限按上百条设计，`includes` 在这个量级毫无压力，
 * 不值得为它加索引或走后端。
 *
 * @param {Array<any>} entries
 * @param {string} filter
 * @returns {Array<any>}
 */
export function filterEntries(entries, filter) {
    const q = (filter || '').trim().toLowerCase();
    if (!q) return entries;
    return entries.filter(e => {
        const name = (e.displayName || '').toLowerCase();
        const full = (e.fullName || '').toLowerCase();
        return name.includes(q) || full.includes(q);
    });
}
