// @ts-check
/**
 * 新建条目向导（右侧抽屉）。
 *
 * 这是全流程最需要打磨的部分（docs/05 第 4 节）：规则写错是这类工具最常见的
 * 错误来源，所以每一步都要可视化验证，而不是让用户凭空写 glob。
 *
 * 三步：输入仓库 → 勾选资产 → 配置处理方式。
 *
 * 设计文档里的"步骤 3：验证规则"被合并进了步骤 2 的实时预览——多一步强制
 * 点击对用户体验是负担，而"勾选后立即显示匹配结果"能达到同样的防错效果。
 *
 * 形态从居中 modal 改成右侧抽屉：modal 会遮住整个列表，用户看不见
 * "我正在加到哪一条"；抽屉贴边，像一个工作面板而不是一次打断。
 */

import { html } from 'htm/preact';
import { useState, useCallback, useEffect } from 'preact/hooks';
import { api } from '../api.js';
import { store } from '../store.js';
import { toast, toastError } from '../toast.js';
import { Button } from '../components/Button.js';
import { formatBytes } from '../format.js';

const KINDS = [
    { value: 'BINARY', label: '单文件替换', hint: '下载后原子替换目标文件，保留备份' },
    { value: 'ARCHIVE', label: '压缩包解压', hint: '解压并覆盖目标目录，保留用户其它文件' },
    { value: 'SOURCE', label: '源码更新', hint: 'git clone / checkout 到指定 tag' },
    { value: 'COLLECTION', label: '仅归档', hint: '按文件名模板落盘，只留最新 N 份' },
];

const STEPS = [
    { n: 1, label: '仓库', hint: '贴 GitHub 地址' },
    { n: 2, label: '资产', hint: '选要拿哪个文件' },
    { n: 3, label: '处理方式', hint: '放到哪、怎么放' },
];

export function WizardView() {
    const [step, setStep] = useState(1);
    const [repoUrl, setRepoUrl] = useState('');
    const [createdEntry, setCreatedEntry] = useState(null);
    const [preview, setPreview] = useState([]);
    const [busy, setBusy] = useState(false);

    // 步骤 2 的勾选结果
    const [selectedAsset, setSelectedAsset] = useState('');
    const [includeGlob, setIncludeGlob] = useState('');

    // 步骤 3 的配置
    const [kind, setKind] = useState('BINARY');
    const [targetDir, setTargetDir] = useState('');
    const [keepVersions, setKeepVersions] = useState(2);
    const [fileNameTpl, setFileNameTpl] = useState('{name}-{version}.{ext}');

    const close = () => store.setWizardOpen(false);

    // Esc 关闭。抽屉不是模态对话框，但用户按 Esc 的期待是一样的。
    useEffect(() => {
        const onKey = e => {
            if (e.key === 'Escape') close();
        };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, []);

    const onCreateEntry = useCallback(async () => {
        const raw = repoUrl.trim();
        if (!raw) {
            toast('请输入仓库地址', 'warn');
            return;
        }
        setBusy(true);
        try {
            const r = await api.createEntry(raw, 'latest');
            const entry = r.entry;
            setCreatedEntry(entry);
            setPreview(r.assetsPreview || []);

            // 刷新列表，让用户看到条目已建
            const list = await api.listEntries();
            store.setEntries(list.entries || []);
            // 顺手选中它，右栏立刻跟上
            store.openEntry(entry.id);

            setStep(2);
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [repoUrl]);

    const onPickAsset = useCallback(name => {
        setSelectedAsset(name);
        setIncludeGlob(name);
    }, []);

    const onFinish = useCallback(async () => {
        if (!createdEntry) return;
        if (!targetDir.trim()) {
            toast('请填写目标目录（绝对路径）', 'warn');
            return;
        }
        setBusy(true);
        try {
            await api.createRule(createdEntry.id, {
                includeGlob: includeGlob.trim() || selectedAsset,
                excludeGlob: null,
                kind,
                targetDir: targetDir.trim(),
                fileNameTpl: kind === 'COLLECTION' ? fileNameTpl : null,
                keepVersions: Number(keepVersions) || 1,
                autoApply: false,
                afterApplyCmd: null,
            });
            toast('条目已创建');
            close();
            const list = await api.listEntries();
            store.setEntries(list.entries || []);
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [createdEntry, includeGlob, selectedAsset, kind, targetDir, keepVersions, fileNameTpl]);

    return html`
        <div class="sheet-backdrop" onClick=${e => { if (e.target === e.currentTarget) close(); }}>
            <div class="sheet" role="dialog" aria-modal="true" aria-label="添加仓库">
                <div class="sheet-header">
                    <h2 class="sheet-title">添加仓库</h2>
                    <${Button} variant="icon" title="关闭" onClick=${close}>×<//>
                </div>

                <div class="wizard-rail">
                    ${STEPS.map(s => html`
                        <div key=${s.n} class=${nodeClass(step, s.n)}>
                            <span class="wizard-node-dot"></span>
                            <span class="wizard-node-text">
                                ${s.label}
                                <span class="wizard-node-hint">${s.hint}</span>
                            </span>
                        </div>
                    `)}
                </div>

                <div class="sheet-body">
                    ${step === 1 && html`
                        <div class="field">
                            <label class="field-label" for="wizard-repo">
                                仓库地址或 owner/repo
                            </label>
                            <input
                                id="wizard-repo"
                                class="input input--mono"
                                placeholder="https://github.com/jqlang/jq"
                                value=${repoUrl}
                                onInput=${e => setRepoUrl(e.currentTarget.value)}
                                onKeyDown=${e => { if (e.key === 'Enter') onCreateEntry(); }}
                                autoFocus
                            />
                            <span class="field-hint">
                                支持完整 URL、owner/repo、带 .git 后缀三种写法。
                            </span>
                        </div>
                    `}

                    ${step === 2 && html`
                        <p class="text-sm text-dim" style="margin-bottom:var(--space-3)">
                            从最近的 release 里选一个资产。也可以直接改成通配符
                            （如 <code class="mono">jq-windows-*.exe</code>），
                            这样每个新版本都能匹配上。
                        </p>

                        ${preview.length === 0
                            ? html`
                                <p class="text-sm text-faint">
                                    没有拿到资产列表。可能是配额用尽、网络不通，
                                    或者这个仓库还没有发布过 release。
                                </p>
                            `
                            : html`
                                <div class="asset-list">
                                    ${preview.map(rel => html`
                                        <div key=${rel.tag}>
                                            <div class="asset-group-title">
                                                <span>${rel.tag}</span>
                                                <span class="asset-group-date">
                                                    ${(rel.assets || []).length} 个资产
                                                </span>
                                            </div>
                                            ${(rel.assets || []).map(a => html`
                                                <label key=${a.name} class="asset-item">
                                                    <input
                                                        type="radio"
                                                        name="asset"
                                                        checked=${selectedAsset === a.name}
                                                        onChange=${() => onPickAsset(a.name)}
                                                    />
                                                    <span class="asset-item-name">${a.name}</span>
                                                    <span class="asset-item-meta">${formatBytes(a.size)}</span>
                                                    ${a.digest
                                                        ? html`<span class="badge badge--ok">有校验和</span>`
                                                        : html`<span class="badge badge--muted">无校验和</span>`}
                                                </label>
                                            `)}
                                        </div>
                                    `)}
                                </div>
                            `}

                        <div class="field" style="margin-top:var(--space-4)">
                            <label class="field-label" for="wizard-glob">匹配规则（glob）</label>
                            <input
                                id="wizard-glob"
                                class="input input--mono"
                                value=${includeGlob}
                                onInput=${e => setIncludeGlob(e.currentTarget.value)}
                                placeholder="jq-windows-amd64.exe"
                            />
                            <span class="field-hint">
                                支持 * 与 ?。规则会应用到每个新版本，所以别写死版本号。
                            </span>
                        </div>
                    `}

                    ${step === 3 && html`
                        <div class="field">
                            <label class="field-label" for="wizard-kind">处理方式</label>
                            <select id="wizard-kind" class="select" value=${kind}
                                onChange=${e => setKind(e.currentTarget.value)}>
                                ${KINDS.map(k => html`
                                    <option key=${k.value} value=${k.value}>${k.label}</option>
                                `)}
                            </select>
                            <span class="field-hint">
                                ${(KINDS.find(k => k.value === kind) || {}).hint || ''}
                            </span>
                        </div>

                        <div class="field">
                            <label class="field-label" for="wizard-dir">目标目录（绝对路径）</label>
                            <input
                                id="wizard-dir"
                                class="input input--mono"
                                placeholder="D:\\tools\\jq"
                                value=${targetDir}
                                onInput=${e => setTargetDir(e.currentTarget.value)}
                            />
                            <span class="field-hint">
                                ${kind === 'SOURCE'
                                    ? '源码会被 clone 到这个目录；目录非空且不是 git 仓库时会被拒绝。'
                                    : '文件会被写到这里。系统目录（Windows、Program Files）会被拒绝。'}
                            </span>
                        </div>

                        ${kind === 'COLLECTION' && html`
                            <div class="field">
                                <label class="field-label" for="wizard-tpl">文件名模板</label>
                                <input
                                    id="wizard-tpl"
                                    class="input input--mono"
                                    value=${fileNameTpl}
                                    onInput=${e => setFileNameTpl(e.currentTarget.value)}
                                />
                                <span class="field-hint">
                                    占位符：{name} {version} {ext}
                                </span>
                            </div>
                        `}

                        ${kind !== 'SOURCE' && html`
                            <div class="field">
                                <label class="field-label" for="wizard-keep">保留版本数</label>
                                <input
                                    id="wizard-keep"
                                    class="input num"
                                    type="number"
                                    min="1"
                                    max="20"
                                    value=${keepVersions}
                                    onInput=${e => setKeepVersions(e.currentTarget.value)}
                                />
                                <span class="field-hint">
                                    ${kind === 'COLLECTION'
                                        ? '归档时只保留最新 N 份，旧的自动清理。'
                                        : '替换时保留 N 个备份，可回滚。'}
                                </span>
                            </div>
                        `}
                    `}
                </div>

                <div class="sheet-actions">
                    <${Button} variant="ghost" onClick=${close}>取消<//>
                    <div class="sheet-actions-spacer"></div>

                    ${step === 2 && html`
                        <${Button} onClick=${() => setStep(1)}>上一步<//>
                        <${Button} variant="primary" disabled=${!includeGlob.trim()}
                            onClick=${() => setStep(3)}>下一步<//>
                    `}
                    ${step === 3 && html`
                        <${Button} onClick=${() => setStep(2)}>上一步<//>
                        <${Button} variant="primary" disabled=${busy} onClick=${onFinish}>
                            ${busy ? '创建中…' : '创建'}
                        <//>
                    `}
                    ${step === 1 && html`
                        <${Button} variant="primary" disabled=${busy} onClick=${onCreateEntry}>
                            ${busy ? '查询中…' : '下一步'}
                        <//>
                    `}
                </div>
            </div>
        </div>
    `;
}

function nodeClass(current, n) {
    const cls = ['wizard-node'];
    if (current === n) cls.push('wizard-node--active');
    else if (current > n) cls.push('wizard-node--done');
    return cls.join(' ');
}
