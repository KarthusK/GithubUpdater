// @ts-check
/**
 * 条目详情：规则、任务、备份。
 *
 * 这一页是"下载 → 应用 → 回滚"全流程的操作台，所以四个动作都要给明确的
 * 前置条件与失败引导（尤其是 FILE_LOCKED 与 GIT_DIRTY 这两种需要用户决策的情况）。
 */

import { html } from 'htm/preact';
import { useState, useEffect, useCallback } from 'preact/hooks';
import { useStore } from '../hooks/useStore.js';
import { selectors, store } from '../store.js';
import { api } from '../api.js';
import { toast, toastError } from '../toast.js';
import { Button } from '../components/Button.js';
import { Dialog } from '../components/Dialog.js';
import { ProgressBar } from '../components/ProgressBar.js';
import {
    formatBytes, formatDate, formatRelative,
    stateBadgeClass, stateLabel, kindLabel,
} from '../format.js';

export function DetailView() {
    const entryId = useStore(selectors.selectedEntryId);
    const entries = useStore(selectors.entries);
    const tasks = useStore(selectors.tasks);

    const [detail, setDetail] = useState(null);
    const [rules, setRules] = useState([]);
    const [loading, setLoading] = useState(true);
    const [confirmDialog, setConfirmDialog] = useState(null);

    const entry = entries.find(e => e.id === entryId);

    const reload = useCallback(async () => {
        if (!entryId) return;
        try {
            const [d, r] = await Promise.all([
                api.getEntry(entryId),
                api.listRules(entryId),
            ]);
            setDetail(d);
            setRules(r.rules || []);
        } catch (e) {
            toastError(e);
        } finally {
            setLoading(false);
        }
    }, [entryId]);

    useEffect(() => {
        setLoading(true);
        reload();
    }, [reload]);

    // 任务状态变化时刷新详情（SSE 改了 store，这里跟着拉一次）
    useEffect(() => {
        if (!entryId) return;
        reload();
    }, [tasks.size]);

    const onCheck = useCallback(async () => {
        try {
            const r = await api.checkOne(entryId);
            toast(`检查完成：${r.outcome}${r.tag ? ' → ' + r.tag : ''}`);
            reload();
        } catch (e) {
            toastError(e);
        }
    }, [entryId, reload]);

    const onDelete = useCallback(() => {
        setConfirmDialog({
            title: '删除条目',
            body: `确定不再跟踪 ${entry ? entry.fullName : ''}？已下载的文件不会被删除。`,
            confirmLabel: '删除',
            variant: 'danger',
            onConfirm: async () => {
                try {
                    await api.deleteEntry(entryId);
                    toast('已删除');
                    store.navigate('list');
                    const list = await api.listEntries();
                    store.setEntries(list.entries || []);
                } catch (e) {
                    toastError(e);
                }
                setConfirmDialog(null);
            },
        });
    }, [entryId, entry]);

    if (!entryId) {
        return html`<div class="empty-state"><p class="muted">未选择条目</p></div>`;
    }
    if (loading && !detail) {
        return html`<div class="empty-state"><p class="muted">加载中…</p></div>`;
    }

    const entryData = (detail && detail.entry) || entry || {};
    const ruleTasks = new Map();
    for (const t of tasks.values()) {
        if (!ruleTasks.has(t.assetRuleId)) ruleTasks.set(t.assetRuleId, []);
        ruleTasks.get(t.assetRuleId).push(t);
    }

    return html`
        <div class="toolbar">
            <${Button} variant="ghost" onClick=${() => store.navigate('list')}>← 返回<//>
            <span class="text-sm">${entryData.fullName || ''}</span>
            <div class="toolbar-spacer"></div>
            <${Button} onClick=${onCheck}>检查<//>
            <${Button} variant="danger" small onClick=${onDelete}>删除条目<//>
        </div>

        <div class="detail-body">
            <div class="detail-section">
                <h3 class="detail-section-title">基本信息</h3>
                <div class="kv">
                    <span class="kv-key">仓库</span>
                    <span class="kv-val mono">${entryData.fullName || '—'}</span>
                    <span class="kv-key">频道</span>
                    <span class="kv-val">${channelLabel(entryData.channel)}</span>
                    <span class="kv-key">最新版本</span>
                    <span class="kv-val mono">${entryData.lastSeenTag || '—'}</span>
                    <span class="kv-key">release id</span>
                    <span class="kv-val mono num">${entryData.lastSeenReleaseId || '—'}</span>
                    <span class="kv-key">上次检查</span>
                    <span class="kv-val">${formatRelative(entryData.lastCheckedAt)}</span>
                </div>
            </div>

            <div class="detail-section">
                <h3 class="detail-section-title">资产规则</h3>
                ${rules.length === 0
                    ? html`<p class="text-sm text-dim">还没有配置资产规则。</p>`
                    : rules.map(r => html`
                        <${RuleItem}
                            key=${r.id}
                            rule=${r}
                            tasks=${ruleTasks.get(r.id) || []}
                            entryTag=${entryData.lastSeenTag}
                            onChanged=${reload}
                        />
                    `)}
            </div>
        </div>

        ${confirmDialog && html`
            <${Dialog}
                title=${confirmDialog.title}
                onClose=${() => setConfirmDialog(null)}
                actions=${[
                    { label: '取消', onClick: () => setConfirmDialog(null) },
                    {
                        label: confirmDialog.confirmLabel,
                        variant: confirmDialog.variant,
                        onClick: confirmDialog.onConfirm,
                    },
                ]}
            >
                <p class="text-sm">${confirmDialog.body}</p>
            <//>
        `}
    `;
}

function channelLabel(channel) {
    if (!channel) return 'latest';
    if (channel === 'latest') return '最新正式版';
    if (channel === 'prerelease') return '含预发布';
    if (channel.startsWith('tag:')) return `固定 tag：${channel.slice(4)}`;
    return channel;
}

/**
 * 一条资产规则 + 它下面的任务。
 * @param {object} props
 */
function RuleItem({ rule, tasks, entryTag, onChanged }) {
    const [busy, setBusy] = useState(false);
    const [backups, setBackups] = useState([]);
    const [showBackups, setShowBackups] = useState(false);

    const activeTask = pickActiveTask(tasks);
    const doneTask = tasks.find(t => t.state === 'DONE');

    const loadBackups = useCallback(async () => {
        try {
            const r = await api.listBackups(rule.id);
            setBackups(r.backups || []);
        } catch (e) {
            toastError(e);
        }
    }, [rule.id]);

    useEffect(() => {
        if (showBackups) loadBackups();
    }, [showBackups, loadBackups]);

    const onDownload = useCallback(async () => {
        setBusy(true);
        try {
            // 需要 asset 的完整信息才能建任务。这里用当前条目已检查到的 tag。
            // 真实资产信息应由后端从最近的 release 缓存里取，但当前设计是
            // 检查时已拿到，前端从 entries 里读不到，所以走一次轻量的 check。
            const r = await api.checkOne(rule.entryId);
            const asset = (r.matchedAssets || [])[0];
            if (!asset) {
                toast('该规则在最新版本里没有匹配到资产', 'warn');
                return;
            }
            await api.createTask({
                assetRuleId: rule.id,
                assetName: asset,
                downloadUrl: r.downloadUrl || '',
                releaseId: r.releaseId || 0,
                releaseTag: r.tag || entryTag || 'unknown',
                assetId: r.assetId || 0,
                expectedSize: r.expectedSize || null,
                expectedDigest: r.expectedDigest || null,
            });
            toast('已加入下载队列');
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [rule, entryTag]);

    const onApplySource = useCallback(async () => {
        setBusy(true);
        try {
            const r = await api.applySource(rule.id, entryTag);
            toast(r.message || '源码已更新');
            onChanged();
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [rule.id, entryTag, onChanged]);

    const onApply = useCallback(async (force = false, unverifiedConfirmed = false) => {
        if (!doneTask) return;
        setBusy(true);
        try {
            const r = await api.applyTask(doneTask.id, { force, unverifiedConfirmed });
            toast(r.message || '已应用');
            onChanged();
        } catch (e) {
            if (e.code === 'FILE_LOCKED') {
                const procs = (e.detail && e.detail.processes) || [];
                const names = procs.map(p => `${p.name} (PID ${p.pid})`).join('、');
                if (confirm(`目标文件被以下进程占用：\n${names}\n\n是否结束这些进程并替换？`)) {
                    setBusy(false);
                    return onApply(true, unverifiedConfirmed);
                }
            } else if (e.code === 'VALIDATION_FAILED'
                    && e.detail && e.detail.requiresUnverifiedConfirm) {
                if (confirm('该资产上游未提供校验和，无法验证完整性。\n\n仍要继续应用吗？')) {
                    setBusy(false);
                    return onApply(force, true);
                }
            } else {
                toastError(e);
            }
        } finally {
            setBusy(false);
        }
    }, [doneTask, onChanged]);

    const onRollback = useCallback(async () => {
        if (!confirm('回滚会用备份覆盖当前文件，确定继续？')) return;
        setBusy(true);
        try {
            const r = await api.rollbackTask(activeTask ? activeTask.id : 0);
            toast(r.message || '已回滚');
            onChanged();
            if (showBackups) loadBackups();
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [activeTask, onChanged, showBackups, loadBackups]);

    return html`
        <div class="rule-item">
            <div class="rule-item-main">
                <div class="row" style="gap:var(--space-2)">
                    <span class="rule-glob">${rule.includeGlob}</span>
                    <span class="badge badge--muted">${kindLabel(rule.kind)}</span>
                </div>
                <div class="rule-target" title=${rule.targetDir}>${rule.targetDir}</div>

                ${activeTask && html`
                    <div style="margin-top:var(--space-2)">
                        <div class="row text-xs text-faint" style="justify-content:space-between">
                            <span>${activeTask.assetName} · ${activeTask.releaseTag}</span>
                            <span class=${stateBadgeClass(activeTask.state)}>
                                ${stateLabel(activeTask.state)}
                            </span>
                        </div>
                        <${ProgressBar}
                            taskId=${activeTask.id}
                            expectedSize=${activeTask.expectedSize}
                            bytesDone=${activeTask.bytesDone}
                            state=${activeTask.state}
                        />
                        ${activeTask.errorMessage && html`
                            <div class="text-xs text-danger">${activeTask.errorMessage}</div>
                        `}
                    </div>
                `}
            </div>

            <div class="col" style="gap:var(--space-1);align-items:flex-end">
                <div class="row">
                    ${rule.kind === 'SOURCE'
                        ? html`<${Button} small disabled=${busy} onClick=${onApplySource}>
                                更新源码<//>`
                        : html`<${Button} small disabled=${busy} onClick=${onDownload}>
                                下载<//>`}
                    ${doneTask && html`
                        <${Button} small variant="primary" disabled=${busy}
                            onClick=${() => onApply()}>应用<//>
                    `}
                    ${activeTask && activeTask.state === 'RUNNING' && html`
                        <${Button} small onClick=${() => api.pauseTask(activeTask.id).catch(toastError)}>
                            暂停<//>
                    `}
                    ${activeTask && activeTask.state === 'PAUSED' && html`
                        <${Button} small onClick=${() => api.resumeTask(activeTask.id).catch(toastError)}>
                            继续<//>
                    `}
                    <${Button} small variant="ghost" onClick=${() => setShowBackups(v => !v)}>
                        备份<//>
                </div>
            </div>
        </div>

        ${showBackups && html`
            <div class="card" style="margin:0 0 var(--space-3) var(--space-4)">
                <div class="row" style="justify-content:space-between;margin-bottom:var(--space-2)">
                    <span class="text-sm text-dim">可回滚的备份</span>
                    <${Button} small variant="ghost" onClick=${loadBackups}>刷新<//>
                </div>
                ${backups.length === 0
                    ? html`<p class="text-xs text-faint">暂无备份</p>`
                    : backups.map(b => html`
                        <div key=${b.id} class="row text-sm" style="padding:var(--space-1) 0">
                            <span class="grow mono text-xs">${b.backupPath}</span>
                            <span class="text-xs text-faint">${formatDate(b.createdAt)}</span>
                            ${!b.exists && html`<span class="badge badge--error">文件已删</span>`}
                            <${Button} small disabled=${!b.exists || busy}
                                onClick=${async () => {
                                    if (!confirm('用这个备份覆盖当前文件？')) return;
                                    setBusy(true);
                                    try {
                                        await api.rollbackBackup(b.id);
                                        toast('已回滚');
                                        loadBackups();
                                        onChanged();
                                    } catch (e) {
                                        toastError(e);
                                    } finally {
                                        setBusy(false);
                                    }
                                }}>回滚<//>
                        </div>
                    `)}
            </div>
        `}
    `;
}

/** 取最该展示的任务。 */
function pickActiveTask(tasks) {
    if (!tasks || tasks.length === 0) return null;
    const ORDER = ['RUNNING', 'VERIFYING', 'DONE', 'PAUSED', 'FAILED', 'QUEUED', 'APPLIED', 'CANCELLED'];
    for (const s of ORDER) {
        const t = tasks.find(x => x.state === s);
        if (t) return t;
    }
    return tasks[0];
}
