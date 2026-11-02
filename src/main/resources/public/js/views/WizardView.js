// @ts-check
/**
 * 新建条目向导。
 *
 * 这是全流程最需要打磨的部分（docs/05 第 4 节）：规则写错是这类工具最常见的
 * 错误来源，所以每一步都要可视化验证，而不是让用户凭空写 glob。
 *
 * 四步：输入仓库 → 勾选资产 → 配置处理方式 → 完成。
 *
 * 设计文档里的"步骤 3：验证规则"被合并进了步骤 2 的实时预览——多一步强制
 * 点击对用户体验是负担，而"勾选后立即显示匹配结果"能达到同样的防错效果。
 */

import { html } from 'htm/preact';
import { useState, useCallback } from 'preact/hooks';
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
        <div class="dialog-backdrop" onClick=${e => { if (e.target === e.currentTarget) close(); }}>
            <div class="dialog" style="max-width:720px" role="dialog" aria-modal="true">
                <div class="dialog-header">新建跟踪条目</div>

                <div class="wizard-steps">
                    ${[1, 2, 3].map(n => html`
                        <div key=${n} class=${stepClass(step, n)}>
                            ${n === 1 ? '① 仓库' : n === 2 ? '② 资产' : '③ 处理方式'}
                        </div>
                    `)}
                </div>

                <div class="dialog-body">
                    ${step === 1 && html`
                        <div class="field">
                            <label class="field-label">仓库地址或 owner/repo</label>
                            <input
                                class="input"
                                placeholder="https://github.com/jqlang/jq"
                                value=${repoUrl}
                                onInput=${e => setRepoUrl(e.currentTarget.value)}
                                onKeyDown=${e => { if (e.key === 'Enter') onCreateEntry(); }}
                                autoFocus
                            />
                            <span class="field-hint">
                                支持完整 URL、owner/repo、带 .git 后缀三种写法
                            </span>
                        </div>
                    `}

                    ${step === 2 && html`
                        <p class="text-sm text-dim" style="margin-top:0">
                            从最近的 release 里选一个资产。也可以直接改成通配符（如
                            <code class="mono">jq-windows-*.exe</code>）。
                        </p>

                        ${preview.length === 0
                            ? html`<p class="text-sm text-faint">没有拿到资产列表，可能是配额用尽或仓库没有 release。</p>`
                            : html`
                                <div class="asset-list">
                                    ${preview.map(rel => html`
                                        <div key=${rel.tag}>
                                            <div class="asset-group-title">
                                                ${rel.tag}${rel.publishedAt
                                                    ? ' · ' + new Date(rel.publishedAt).toLocaleDateString('zh-CN')
                                                    : ''}
                                            </div>
                                            ${(rel.assets || []).map(a => html`
                                                <label key=${a.name} class="asset-item">
                                                    <input
                                                        type="radio"
                                                        name="asset"
                                                        checked=${selectedAsset === a.name}
                                                        onChange=${() => onPickAsset(a.name)}
                                                    />
                                                    <span class="asset-item-name mono">${a.name}</span>
                                                    <span class="asset-item-meta num">${formatBytes(a.size)}</span>
                                                    ${a.digest
                                                        ? html`<span class="badge badge--ok">有校验和</span>`
                                                        : html`<span class="badge badge--muted">无校验和</span>`}
                                                </label>
                                            `)}
                                        </div>
                                    `)}
                                </div>
                            `}

                        <div class="field" style="margin-top:var(--space-3)">
                            <label class="field-label">匹配规则（glob）</label>
                            <input
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
                            <label class="field-label">处理方式</label>
                            <select class="select" value=${kind}
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
                            <label class="field-label">目标目录（绝对路径）</label>
                            <input
                                class="input input--mono"
                                placeholder="D:\\tools\\jq"
                                value=${targetDir}
                                onInput=${e => setTargetDir(e.currentTarget.value)}
                            />
                            <span class="field-hint">
                                ${kind === 'SOURCE'
                                    ? '源码会被 clone 到这个目录；目录非空且不是 git 仓库时会被拒绝'
                                    : '文件会被写到这里。系统目录（Windows、Program Files）会被拒绝'}
                            </span>
                        </div>

                        ${kind === 'COLLECTION' && html`
                            <div class="field">
                                <label class="field-label">文件名模板</label>
                                <input
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
                                <label class="field-label">保留版本数</label>
                                <input
                                    class="input num"
                                    type="number"
                                    min="1"
                                    max="20"
                                    value=${keepVersions}
                                    onInput=${e => setKeepVersions(e.currentTarget.value)}
                                />
                                <span class="field-hint">
                                    ${kind === 'COLLECTION'
                                        ? '归档时只保留最新 N 份，旧的自动清理'
                                        : '替换时保留 N 个备份，可回滚'}
                                </span>
                            </div>
                        `}
                    `}
                </div>

                <div class="dialog-footer">
                    <${Button} onClick=${close}>取消<//>
                    ${step === 2 && html`
                        <${Button} onClick=${() => setStep(1)}>上一步<//>
                        <${Button} variant="primary" disabled=${!includeGlob.trim()}
                            onClick=${() => setStep(3)}>下一步<//>
                    `}
                    ${step === 3 && html`
                        <${Button} onClick=${() => setStep(2)}>上一步<//>
                        <${Button} variant="primary" disabled=${busy} onClick=${onFinish}>
                            ${busy ? '创建中…' : '完成'}
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

function stepClass(current, n) {
    const cls = ['wizard-step'];
    if (current === n) cls.push('wizard-step--active');
    else if (current > n) cls.push('wizard-step--done');
    return cls.join(' ');
}

