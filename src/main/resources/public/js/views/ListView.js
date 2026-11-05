// @ts-check
/**
 * 左栏：条目台账。
 *
 * 与旧版的区别：这里不再是「列表页」，而是常驻的主控栏。
 * 点一行是"把右栏切到那条"，不是"跳转到另一个页面"——
 * 所以看不到 `.list-header` 表头，也不再有 `[详情]` 按钮。
 *
 * 行高从 `--h-row` 令牌读，不在 JS 里硬编码：虚拟滚动按固定行高算位置，
 * 一旦 CSS 改了行高而 JS 没跟上，所有行的位置就整体错位（滚动到哪都是错的）。
 */

import { html } from 'htm/preact';
import { useCallback, useMemo } from 'preact/hooks';
import { useStore } from '../hooks/useStore.js';
import { selectors, store, filterEntries } from '../store.js';
import { api } from '../api.js';
import { toastError } from '../toast.js';
import { readCssPx } from '../theme.js';
import { VirtualList } from '../components/VirtualList.js';
import { Button } from '../components/Button.js';
import { formatRelative, isQuotaLow, isMitmFailure, stateLabel } from '../format.js';

/** 行高与 CSS 同源。模块顶层只读一次：令牌在运行期不变，读一次就够。
 *  64 是读不到令牌时的兜底值，与 `--h-row` 的默认值一致。 */
const ROW_HEIGHT = readCssPx('--h-row', 64);

export function ListView() {
    const entries = useStore(selectors.entries);
    const quota = useStore(selectors.quota);
    const checksInProgress = useStore(selectors.checksInProgress);
    const selectedEntryId = useStore(selectors.selectedEntryId);
    const tasks = useStore(selectors.tasks);
    const filter = useStore(selectors.filter);

    const visible = useMemo(() => filterEntries(entries, filter), [entries, filter]);
    const taskByRule = useMemo(() => buildTaskIndex(tasks), [tasks]);

    const onCheckAll = useCallback(async () => {
        try {
            store.setChecksInProgress(true);
            await api.checkAll();
        } catch (e) {
            store.setChecksInProgress(false);
            toastError(e);
        }
    }, []);

    const onActiveChange = useCallback(id => store.openEntry(Number(id)), []);

    const renderRow = useCallback(item => html`
        <${EntryRow}
            entry=${item}
            task=${taskByRule.get(item.id)}
            selected=${item.id === selectedEntryId}
        />
    `, [taskByRule, selectedEntryId]);

    // 配额不足时禁用「全部检查」——这是文档要求的引导。
    // 注意 remaining < 0 表示未知，不能当成"不足"。
    const quotaLow = isQuotaLow(quota);
    const checkDisabled = checksInProgress || quotaLow || entries.length === 0;

    return html`
        <aside class="sidebar">
            <div class="sidebar-head">
                <input
                    class="sidebar-search"
                    type="search"
                    placeholder="过滤仓库"
                    aria-label="过滤仓库"
                    value=${filter}
                    onInput=${e => store.setFilter(e.currentTarget.value)}
                />
                <span class="sidebar-count" title="跟踪中的仓库数">
                    ${visible.length}${visible.length !== entries.length ? '/' + entries.length : ''}
                </span>
                <${Button}
                    variant="quiet"
                    small
                    disabled=${checkDisabled}
                    title=${quotaLow ? '配额不足' : '依次检查所有仓库'}
                    onClick=${onCheckAll}
                >${checksInProgress ? '检查中…' : '全部检查'}<//>
            </div>

            <${VirtualList}
                items=${visible}
                rowHeight=${ROW_HEIGHT}
                renderRow=${renderRow}
                activeId=${selectedEntryId}
                onActiveChange=${onActiveChange}
                emptyText=${emptyText(entries.length, filter)}
            />
        </aside>
    `;
}

/**
 * 空状态分三种，文案必须不同：第一次用 / 过滤没命中 / 刚清空。
 * 一句通用的"暂无数据"会让用户不知道该做什么。
 */
function emptyText(total, filter) {
    if (total === 0) return '还没有跟踪任何仓库。点右上角「添加仓库」贴一个 GitHub 地址。';
    if (filter) return `没有匹配「${filter}」的仓库。`;
    return '暂无条目。';
}

/**
 * 把任务按 assetRuleId 归组，取"最值得显示"的那一个。
 *
 * 优先级：RUNNING > VERIFYING > DONE（待应用）> FAILED > PAUSED > 其它。
 * 这样左栏里看到的状态就是用户最该关心的那个。
 * @param {Map<number, any>} tasks
 */
function buildTaskIndex(tasks) {
    const PRIORITY = {
        RUNNING: 6, VERIFYING: 5, DONE: 4, FAILED: 3, PAUSED: 2, QUEUED: 1,
    };
    /** @type {Map<number, any>} */
    const index = new Map();

    for (const t of tasks.values()) {
        const cur = index.get(t.assetRuleId);
        const p = PRIORITY[t.state] || 0;
        const cp = cur ? (PRIORITY[cur.state] || 0) : -1;
        if (p > cp) index.set(t.assetRuleId, t);
    }
    return index;
}

/**
 * @param {object} props
 * @param {any} props.entry
 * @param {any} props.task
 * @param {boolean} props.selected
 */
function EntryRow({ entry, task, selected }) {
    const onOpen = () => store.openEntry(entry.id);

    const name = entry.displayName || entry.fullName;
    // 建条目时 displayName 默认就等于 fullName，此时两行是同一串文字，
    // 重复显示既浪费高度又显得没做完。相同则合并成一行。
    const sameAsFull = name === entry.fullName;
    const showUpdate = entry.hasUpdate === true;

    // 行状态决定左侧刻线颜色：出错 > 需要处理 > 正在跑 > 无
    let rowState = '';
    let badge = null;

    // 检查失败必须优先于任务状态显示。
    //
    // 截图验证时发现的问题：某个条目历史上有"已应用"的下载任务，
    // 于是左栏一直显示绿色「已应用」，而这一轮的检查其实因为证书被拦截
    // 彻底失败了——用户扫一眼左栏会以为一切正常，右侧却在报警。
    // 上一次下载成功不代表这一次检查成功，两者是不同的事实。
    if (entry.lastCheckStatus === 'ERROR') {
        rowState = 'error';
        badge = isMitmFailure(entry)
            ? html`<span class="badge badge--error entry-badge">证书被拦截</span>`
            : html`<span class="badge badge--error entry-badge">检查失败</span>`;
        // 但正在下载的任务仍然更紧急：用户此刻就盯着进度
        if (task && (task.state === 'RUNNING' || task.state === 'VERIFYING')) {
            rowState = 'busy';
            badge = html`<span class="badge badge--live entry-badge">${stateLabel(task.state)}</span>`;
        }
    } else if (task) {
        rowState = task.state === 'FAILED' ? 'error' : 'busy';
        badge = html`<span class="badge badge--live entry-badge">${stateLabel(task.state)}</span>`;
    } else if (showUpdate) {
        // hasUpdate 只在检查时（REST 响应 / SSE check.result）存在；
        // 刷新页面后它不在快照里，此时不显示"有新版本"而不是猜。
        rowState = 'update';
        badge = html`<span class="badge badge--update entry-badge">有新版本</span>`;
    } else if (!entry.lastSeenTag) {
        badge = html`<span class="badge badge--muted entry-badge">未检查</span>`;
    }

    const cls = ['entry-row'];
    if (rowState) cls.push(`entry-row--${rowState}`);

    return html`
        <div
            class=${cls.join(' ')}
            aria-selected=${selected ? 'true' : 'false'}
            onClick=${onOpen}
        >
            <div class="entry-row-top">
                <span class=${sameAsFull ? 'entry-name entry-name--mono' : 'entry-name'}>${name}</span>
                <span class="entry-time">${formatRelative(entry.lastCheckedAt)}</span>
            </div>
            <div class="entry-row-bottom">
                ${sameAsFull
                    ? html`<span class="entry-sub">${entry.ruleCount || 0} 条规则</span>`
                    : html`<span class="entry-sub">${entry.fullName}</span>`}
                ${badge}
            </div>
        </div>
    `;
}
