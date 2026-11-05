// @ts-check
/**
 * 右栏：条目详情。
 *
 * 这一页是"下载 → 应用 → 回滚"全流程的操作台，所以四个动作都要给明确的
 * 前置条件与失败引导（尤其是 FILE_LOCKED 与 GIT_DIRTY 这两种需要用户决策的情况）。
 *
 * 与旧版的区别：
 *   · 没有顶部 toolbar，标题区直接是 owner/repo + 动作，左栏就是导航
 *   · 三段原生 confirm() 全部换成应用内确认框（见 components/confirm.js）
 *   · 最上面是版本刻度，回答"我落后多少"
 */

import { html } from 'htm/preact';
import { useState, useEffect, useCallback } from 'preact/hooks';
import { useStore } from '../hooks/useStore.js';
import { selectors, store } from '../store.js';
import { api } from '../api.js';
import { toast, toastError } from '../toast.js';
import { Button } from '../components/Button.js';
import { Menu } from '../components/Menu.js';
import { VersionRail } from '../components/VersionRail.js';
import { ProgressBar } from '../components/ProgressBar.js';
import { confirmAsync } from '../components/confirm.js';
import { TlsBanner } from '../components/TlsBanner.js';
import {
    formatDate, formatRelative,
    stateBadgeClass, stateLabel, kindLabel, isMitmFailure,
} from '../format.js';

export function DetailView() {
    const entryId = useStore(selectors.selectedEntryId);
    const entries = useStore(selectors.entries);
    const tasks = useStore(selectors.tasks);

    const [detail, setDetail] = useState(null);
    const [rules, setRules] = useState([]);
    const [loading, setLoading] = useState(true);
    /** 自定义 CA 状态：诊断横幅要据此决定"还提不提"。 @type {any} */
    const [customCa, setCustomCa] = useState(null);

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
            // CA 状态决定诊断横幅是否还要显示。单独取、失败不影响主内容——
            // 设置接口挂了不该让详情页白屏。
            try {
                const s = await api.getSettings();
                setCustomCa(s && s.customCa);
            } catch (e) {
                setCustomCa(null);
            }
        } catch (e) {
            toastError(e);
        } finally {
            setLoading(false);
        }
    }, [entryId]);

    useEffect(() => {
        setLoading(true);
        setDetail(null);
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
            // 失败时必须显示后端给的原因，而不是只报一个 "ERROR" 枚举值。
            // 那句原因是后端结合证书签发者拼出来的，是用户唯一能照着做的线索。
            if (r.outcome === 'ERROR') {
                toast(r.message || '检查失败', 'error');
            } else {
                toast(`检查完成：${r.outcome}${r.tag ? ' → ' + r.tag : ''}`);
            }
            // 先让 store 知道这次的失败状态，诊断横幅才能立刻出现
            store.patchEntry(entryId, {
                lastCheckStatus: r.lastCheckStatus || r.outcome,
                lastCheckError: r.lastCheckError || '',
                lastTlsIssuer: r.lastTlsIssuer || '',
                lastCheckedAt: Date.now(),
            });
            reload();
        } catch (e) {
            toastError(e);
        }
    }, [entryId, reload]);

    const onDelete = useCallback(async () => {
        const ok = await confirmAsync({
            title: '停止跟踪这个仓库？',
            body: html`
                <p>将从跟踪列表里移除 <strong>${entry ? entry.fullName : ''}</strong>。</p>
                <p class="text-xs text-faint" style="margin-top:var(--space-2)">
                    已下载的文件和备份不会被删除，只是不再跟踪新版本。
                </p>
            `,
            confirmLabel: '停止跟踪',
            variant: 'danger',
        });
        if (!ok) return;

        try {
            await api.deleteEntry(entryId);
            toast('已停止跟踪');
            store.closeEntry();
            const list = await api.listEntries();
            store.setEntries(list.entries || []);
        } catch (e) {
            toastError(e);
        }
    }, [entryId, entry]);

    // ── 三种空态 ────────────────────────────────────────────
    if (!entryId) return html`<${EntriesOverview} />`;

    if (loading && !detail) {
        // 骨架而不是"加载中…"三个字：详情页内容很多，先给出结构，
        // 加载完成时不会整屏跳动。
        return html`
            <div class="content-inner">
                <div class="content-head">
                    <div class="content-head-main">
                        <div class="skeleton skeleton--title"></div>
                        <div class="skeleton skeleton--sub"></div>
                    </div>
                </div>
                <div class="skeleton skeleton--rail"></div>
                <div class="skeleton skeleton--block"></div>
                <div class="skeleton skeleton--block"></div>
            </div>
        `;
    }

    const entryData = (detail && detail.entry) || entry || {};
    /** @type {Map<number, any[]>} */
    const ruleTasks = new Map();
    for (const t of tasks.values()) {
        if (!ruleTasks.has(t.assetRuleId)) ruleTasks.set(t.assetRuleId, []);
        ruleTasks.get(t.assetRuleId).push(t);
    }

    const updated = entryData.hasUpdate === true;
    // 后端不返回 appliedTag（Entry 里只有 lastSeenTag）。本地实际装的是
    // 哪一版只能从"已应用"的任务记录里推导：取最近一次 APPLIED 的 releaseTag。
    const appliedTag = latestAppliedTag(tasks, rules);

    return html`
        <div class="content-inner">
            <div class="content-head">
                <div class="content-head-main">
                    <h1 class="content-title content-title--ascii">${entryData.fullName || '—'}</h1>
                    <div class="content-subtitle">
                        ${channelLabel(entryData.channel)}
                        ${entryData.lastCheckedAt
                            ? html`<span class="text-faint"> · 上次检查 ${formatRelative(entryData.lastCheckedAt)}</span>`
                            : html`<span class="text-faint"> · 尚未检查</span>`}
                    </div>
                </div>
                <div class="content-head-actions">
                    <${Button} onClick=${onCheck}>检查<//>
                    <${Menu}
                        items=${[
                            { label: '停止跟踪此仓库', danger: true, onClick: onDelete },
                        ]}
                    />
                </div>
            </div>

            <${VersionRail}
                appliedTag=${appliedTag}
                upstreamTag=${entryData.lastSeenTag || ''}
                hasUpdate=${updated}
            />

            ${renderCheckFailure(entryData, customCa)}

            <section class="section">
                <div class="section-head">
                    <h2 class="section-title">资产规则</h2>
                    <span class="section-meta">${rules.length} 条</span>
                </div>
                ${rules.length === 0
                    ? html`
                        <div class="empty-state">
                            <div class="empty-state-title">还没有配置资产规则</div>
                            <div class="empty-state-hint">
                                资产规则决定"从每个 release 里拿哪个文件、放到哪里"。
                                现在这个仓库只会被检查版本，不会下载任何东西。
                            </div>
                        </div>
                    `
                    : rules.map(r => html`
                        <${RuleItem}
                            key=${r.id}
                            rule=${r}
                            tasks=${ruleTasks.get(r.id) || []}
                            entryTag=${entryData.lastSeenTag}
                            onChanged=${reload}
                        />
                    `)}
            </section>

            <section class="section">
                <div class="section-head">
                    <h2 class="section-title">仓库信息</h2>
                </div>
                <div class="kv">
                    <span class="kv-key">仓库</span>
                    <span class="kv-val mono">${entryData.fullName || '—'}</span>
                    <span class="kv-key">频道</span>
                    <span class="kv-val">${channelLabel(entryData.channel)}</span>
                    <span class="kv-key">最新 tag</span>
                    <span class="kv-val mono">${entryData.lastSeenTag || '—'}</span>
                    <span class="kv-key">release id</span>
                    <span class="kv-val mono num">${entryData.lastSeenReleaseId || '—'}</span>
                    <span class="kv-key">上次检查</span>
                    <span class="kv-val">${formatRelative(entryData.lastCheckedAt)}</span>
                </div>
            </section>
        </div>
    `;
}

/**
 * 右栏在没有选中条目时的内容。
 *
 * 这里做一件有用的事而不是放一句"未选择条目"：给出这个工具的用法摘要。
 * 用户第一次打开面板看到的通常是这一屏。
 */
function EntriesOverview() {
    const entries = useStore(selectors.entries);
    const tasks = useStore(selectors.tasks);

    const undone = [...tasks.values()].filter(t => t.state === 'DONE').length;

    if (entries.length === 0) {
        return html`
            <div class="content-inner">
                <div class="empty-state" style="min-height:60vh">
                    <div class="empty-state-title">还没有跟踪任何仓库</div>
                    <div class="empty-state-hint">
                        贴一个 GitHub 地址进来，选一个资产，剩下的交给它：
                        有新版本时下载、校验、替换、保留备份，随时能回滚。
                    </div>
                    <${Button} variant="primary" onClick=${() => store.setWizardOpen(true)}>
                        添加第一个仓库
                    <//>
                </div>
            </div>
        `;
    }

    return html`
        <div class="content-inner">
            <div class="content-head">
                <div class="content-head-main">
                    <h1 class="content-title">
                        ${entries.length} 个仓库
                    </h1>
                    <div class="content-subtitle">
                        从左侧选一个查看版本与资产
                        ${undone > 0 && html`
                            <span class="text-signal"> · ${undone} 个已下载待应用</span>
                        `}
                    </div>
                </div>
            </div>
            <p class="prose">
                这个工具不常驻轮询。世界只在你点「全部检查」或单条「检查」时变化——
                省掉的每一次请求都是你配额里省下来的一次。
            </p>
        </div>
    `;
}

/**
 * 检查失败的展示。
 *
 * 分两条路，因为用户要做的事完全不同：
 *   · 中间人劫持 → 专用横幅，告诉他去配 CA 或关掉代理
 *   · 其它失败（断网、404、限流）→ 一行通用提示，不误导他去翻代理设置
 *
 * 失败原因来自数据库（last_check_error），所以刷新页面后仍然在——
 * 这正是把状态落库的理由：用户去处理问题再回来，线索不能消失。
 *
 * @param {any} entry
 * @param {any} customCa
 */
function renderCheckFailure(entry, customCa) {
    if (!entry || entry.lastCheckStatus !== 'ERROR') return null;

    // 中间人用专门的横幅，这里不再重复一遍
    if (isMitmFailure(entry)) {
        return html`<${TlsBanner} entry=${entry} customCa=${customCa} />`;
    }

    const reason = entry.lastCheckError || '检查失败，原因未知';
    return html`
        <div class="notice notice--error" role="status">
            <div class="notice-main">
                <div class="notice-title">上次检查失败</div>
                <div class="notice-body">${reason}</div>
            </div>
        </div>
    `;
}

function channelLabel(channel) {    if (!channel) return '最新正式版';
    if (channel === 'latest') return '最新正式版';
    if (channel === 'prerelease') return '含预发布';
    if (channel.startsWith('tag:')) return `固定 tag：${channel.slice(4)}`;
    return channel;
}

/**
 * 从任务记录里推导"本地实际应用的是哪个版本"。
 *
 * 后端 Entry 没有 applied_tag 字段，所以这是唯一的信息来源：
 * 该条目下所有规则的 APPLIED 任务里，releaseTag 最新的那个。
 *
 * 一条 APPLIED 都没有时返回空字符串——调用方据此显示"未知"。
 * 不要返回 "未应用"：那是在没有证据的情况下下结论。
 *
 * @param {Map<number, any>} tasks
 * @param {Array<any>} rules 该条目的规则（用来把任务过滤到本条目）
 * @returns {string}
 */
function latestAppliedTag(tasks, rules) {
    const ruleIds = new Set(rules.map(r => r.id));
    let best = '';
    for (const t of tasks.values()) {
        if (t.state !== 'APPLIED') continue;
        if (!ruleIds.has(t.assetRuleId)) continue;
        const tag = (t.releaseTag || '').trim();
        if (!tag || tag === 'unknown') continue;
        // 任务没有时间戳字段时，只能靠"后遍历到的覆盖先遍历到的"，
        // 但 listTasks 按 created_at DESC 返回，所以第一个命中的就是最新的。
        if (!best) best = tag;
    }
    return best;
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
                const ok = await confirmAsync({
                    title: '目标文件正在被占用',
                    body: html`
                        <p>以下进程持有目标文件的句柄，替换前必须先结束它们：</p>
                        <ul class="dialog-list">
                            ${procs.map(p => html`<li key=${p.pid}>${p.name} · PID ${p.pid}</li>`)}
                        </ul>
                        <p class="text-xs text-faint" style="margin-top:var(--space-3)">
                            结束进程会造成该程序未保存的数据丢失。
                        </p>
                    `,
                    confirmLabel: '结束进程并替换',
                    variant: 'danger',
                });
                if (ok) {
                    setBusy(false);
                    return onApply(true, unverifiedConfirmed);
                }
            } else if (e.code === 'VALIDATION_FAILED'
                    && e.detail && e.detail.requiresUnverifiedConfirm) {
                const ok = await confirmAsync({
                    title: '这个版本没有校验和',
                    body: html`
                        <p>上游没有为这个资产提供 SHA-256，无法验证下载内容是否完整、
                            是否与发布者上传的一致。</p>
                        <p class="text-xs text-faint" style="margin-top:var(--space-2)">
                            跳过校验意味着：如果文件在传输或存储过程中被替换，你不会知道。
                        </p>
                    `,
                    confirmLabel: '仍然应用',
                    variant: 'danger',
                });
                if (ok) {
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
        const ok = await confirmAsync({
            title: '用备份覆盖当前文件？',
            body: html`
                <p>当前的 <span class="mono">${activeTask ? activeTask.assetName : '目标文件'}</span>
                    会被备份里的内容覆盖。</p>
            `,
            confirmLabel: '回滚',
            variant: 'danger',
        });
        if (!ok) return;

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

    // 左刻线颜色：有错误 > 在跑 > 待应用 > 无
    let rail = '';
    if (activeTask && activeTask.state === 'FAILED') rail = 'rule-item--error';
    else if (activeTask && (activeTask.state === 'RUNNING' || activeTask.state === 'VERIFYING')) rail = 'rule-item--busy';
    else if (doneTask) rail = 'rule-item--update';

    return html`
        <div class=${rail ? `rule-item ${rail}` : 'rule-item'}>
            <div class="rule-item-main">
                <div class="rule-head">
                    <span class="rule-glob">${rule.includeGlob}</span>
                    <span class="badge badge--muted">${kindLabel(rule.kind)}</span>
                </div>
                <div class="rule-target" title=${rule.targetDir}>${rule.targetDir}</div>

                ${activeTask && html`
                    <div class="rule-task">
                        <div class="rule-task-head">
                            <span class="rule-task-name">
                                ${activeTask.assetName} · ${activeTask.releaseTag}
                            </span>
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
                            <div class="rule-error">${activeTask.errorMessage}</div>
                        `}
                    </div>
                `}
            </div>

            <div class="rule-actions">
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
                ${activeTask && activeTask.state === 'DONE' && html`
                    <${Button} small variant="ghost" disabled=${busy} onClick=${onRollback}>
                        回滚<//>
                `}
                <${Button} small variant="quiet" onClick=${() => setShowBackups(v => !v)}>
                    备份${backups.length > 0 ? ` ${backups.length}` : ''}
                <//>
            </div>
        </div>

        ${showBackups && html`
            <div class="backup-panel">
                <div class="row" style="justify-content:space-between;margin-bottom:var(--space-2)">
                    <span class="text-xs text-dim">可回滚的备份</span>
                    <${Button} small variant="quiet" onClick=${loadBackups}>刷新<//>
                </div>
                ${backups.length === 0
                    ? html`<p class="text-xs text-faint">暂无备份。应用一次之后就会产生。</p>`
                    : backups.map(b => html`
                        <div key=${b.id} class="backup-row">
                            <span class="backup-path" title=${b.backupPath}>${b.backupPath}</span>
                            ${b.releaseTag && html`
                                <span class="badge badge--muted">${b.releaseTag}</span>
                            `}
                            ${!b.exists && html`<span class="badge badge--error">文件已删</span>`}
                            <span class="backup-time">${formatDate(b.createdAt)}</span>
                            <${Button} small disabled=${!b.exists || busy}
                                onClick=${async () => {
                                    const ok = await confirmAsync({
                                        title: '用这个备份覆盖当前文件？',
                                        body: html`<p class="mono text-xs">${b.backupPath}</p>`,
                                        confirmLabel: '回滚',
                                        variant: 'danger',
                                    });
                                    if (!ok) return;

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
