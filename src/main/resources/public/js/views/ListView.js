// @ts-check
/** 条目列表（虚拟滚动）。这是主界面。 */

import { html } from 'htm/preact';
import { useCallback } from 'preact/hooks';
import { useStore } from '../hooks/useStore.js';
import { selectors, store } from '../store.js';
import { api } from '../api.js';
import { toast, toastError } from '../toast.js';
import { VirtualList } from '../components/VirtualList.js';
import { Button } from '../components/Button.js';
import { formatRelative, formatQuota, isQuotaLow, stateBadgeClass, stateLabel } from '../format.js';

const ROW_HEIGHT = 48;

export function ListView() {
    const entries = useStore(selectors.entries);
    const quota = useStore(selectors.quota);
    const checksInProgress = useStore(selectors.checksInProgress);
    const selectedEntryId = useStore(selectors.selectedEntryId);
    const tasks = useStore(selectors.tasks);

    // 每个条目的任务状态汇总：只要有一个任务在跑就显示进度
    const taskByRule = buildTaskIndex(tasks);

    const onCheckAll = useCallback(async () => {
        try {
            store.setChecksInProgress(true);
            await api.checkAll();
        } catch (e) {
            store.setChecksInProgress(false);
            toastError(e);
        }
    }, []);

    const onOpenWizard = useCallback(() => store.setWizardOpen(true), []);

    const onActiveChange = useCallback(id => {
        store.navigate('detail', id);
    }, []);

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

    return html`
        <div class="toolbar">
            <${Button}
                variant="primary"
                disabled=${checksInProgress || quotaLow}
                onClick=${onCheckAll}
            >${checksInProgress ? '检查中…' : '全部检查'}<//>
            <${Button} onClick=${onOpenWizard}>新建条目<//>
            <div class="toolbar-spacer"></div>
            ${quotaLow && html`
                <span class="text-xs text-danger">配额不足，请配置 Token 或等待重置</span>
            `}
        </div>

        <div class="list-header">
            <span>名称</span>
            <span>状态</span>
            <span>上次检查</span>
            <span class="list-header-actions">操作</span>
        </div>

        <${VirtualList}
            items=${entries}
            rowHeight=${ROW_HEIGHT}
            renderRow=${renderRow}
            activeId=${selectedEntryId}
            onActiveChange=${onActiveChange}
            emptyText="还没有跟踪任何仓库。点「新建条目」开始。"
        />
    `;
}

/**
 * 把任务按 assetRuleId 归组，取"最值得显示"的那一个。
 *
 * 优先级：RUNNING > VERIFYING > DONE（待应用）> FAILED > PAUSED > 其它。
 * 这样列表里看到的状态就是用户最该关心的那个。
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
    const hasUpdate = entry.lastSeenTag && entry.lastSeenTag !== entry.appliedTag;
    const onOpen = () => store.navigate('detail', entry.id);

    return html`
        <div
            class="entry-row"
            aria-selected=${selected ? 'true' : 'false'}
            onClick=${onOpen}
        >
            <div class="col" style="gap:0;min-width:0">
                <span class="entry-name">${entry.displayName || entry.fullName}</span>
                <span class="entry-sub">${entry.fullName}</span>
            </div>

            <div>
                ${task ? html`
                    <span class=${stateBadgeClass(task.state)}>${stateLabel(task.state)}</span>
                ` : (hasUpdate
                    ? html`<span class="badge badge--update">有新版本</span>`
                    : html`<span class="badge badge--muted">${entry.lastSeenTag ? '无变化' : '未检查'}</span>`
                )}
            </div>

            <span class="text-xs text-dim num">${formatRelative(entry.lastCheckedAt)}</span>

            <div class="entry-actions" onClick=${e => e.stopPropagation()}>
                <${Button} small onClick=${onOpen}>详情<//>
            </div>
        </div>
    `;
}
