// @ts-check
/** 设置页。token 只写不读——服务端从不返回明文。 */

import { html } from 'htm/preact';
import { useState, useEffect, useCallback } from 'preact/hooks';
import { api } from '../api.js';
import { store } from '../store.js';
import { toast, toastError } from '../toast.js';
import { Button } from '../components/Button.js';
import { formatQuota, formatResetIn } from '../format.js';

export function SettingsView() {
    const [settings, setSettings] = useState(null);
    const [quota, setQuota] = useState(null);
    const [tokenInput, setTokenInput] = useState('');
    const [busy, setBusy] = useState(false);

    const reload = useCallback(async () => {
        try {
            const [s, q] = await Promise.all([api.getSettings(), api.quota()]);
            setSettings(s);
            setQuota(q);
        } catch (e) {
            toastError(e);
        }
    }, []);

    useEffect(() => { reload(); }, [reload]);

    const save = useCallback(async patch => {
        setBusy(true);
        try {
            await api.patchSettings(patch);
            await reload();
            toast('已保存');
        } catch (e) {
            toastError(e);
        } finally {
            setBusy(false);
        }
    }, [reload]);

    const onSaveToken = useCallback(() => {
        const v = tokenInput.trim();
        if (!v) {
            toast('请输入 Token，或点「清除 Token」', 'warn');
            return;
        }
        save({ githubToken: v }).then(() => setTokenInput(''));
    }, [tokenInput, save]);

    const onClearToken = useCallback(() => {
        if (!confirm('清除 Token 后将退回匿名访问（每小时 60 次配额）。确定？')) return;
        save({ githubToken: null });
    }, [save]);

    if (!settings) {
        return html`<div class="empty-state"><p class="muted">加载中…</p></div>`;
    }

    return html`
        <div class="toolbar">
            <${Button} variant="ghost" onClick=${() => store.navigate('list')}>← 返回<//>
            <span class="text-sm">设置</span>
        </div>

        <div class="settings-body">
            <div class="card" style="margin-bottom:var(--space-4)">
                <h3 class="card-title">GitHub 访问令牌</h3>
                <p class="text-sm text-dim" style="margin-top:0">
                    匿名访问每小时只有 60 次配额，配置 Token 后是 5000 次。
                    Token 只保存在本机 <code class="mono">data/config.json</code>。
                </p>
                <div class="field">
                    <label class="field-label">
                        当前状态：
                        ${settings.githubTokenConfigured
                            ? html`<span class="badge badge--ok">已配置</span>`
                            : html`<span class="badge badge--muted">匿名访问</span>`}
                    </label>
                    <input
                        class="input input--mono"
                        type="password"
                        placeholder=${settings.githubTokenConfigured
                            ? '已配置，填写新值可替换'
                            : 'ghp_xxxxxxxxxxxx'}
                        value=${tokenInput}
                        onInput=${e => setTokenInput(e.currentTarget.value)}
                    />
                    <span class="field-hint">
                        需要 <code class="mono">public_repo</code> 权限即可；
                        访问私有仓库才需要更大的权限。Token 不会回传给界面。
                    </span>
                </div>
                <div class="row">
                    <${Button} variant="primary" disabled=${busy} onClick=${onSaveToken}>保存<//>
                    ${settings.githubTokenConfigured && html`
                        <${Button} variant="danger" disabled=${busy} onClick=${onClearToken}>
                            清除 Token
                        <//>
                    `}
                </div>
            </div>

            <div class="card" style="margin-bottom:var(--space-4)">
                <h3 class="card-title">配额</h3>
                <div class="kv">
                    <span class="kv-key">剩余</span>
                    <span class="kv-val num">${formatQuota(quota)}</span>
                    <span class="kv-key">模式</span>
                    <span class="kv-val">${quota && quota.authenticated ? '已认证' : '匿名'}</span>
                    <span class="kv-key">重置</span>
                    <span class="kv-val">${quota ? formatResetIn(quota.secondsUntilReset) : '—'}</span>
                </div>
            </div>

            <div class="card" style="margin-bottom:var(--space-4)">
                <h3 class="card-title">下载</h3>
                <div class="field">
                    <label class="field-label">同时下载任务数</label>
                    <input
                        class="input num"
                        type="number"
                        min="1"
                        max="8"
                        value=${settings.maxParallelTasks}
                        onChange=${e => save({ maxParallelTasks: Number(e.currentTarget.value) })}
                    />
                    <span class="field-hint">
                        默认 2。并发过高会互相抢带宽，总耗时不一定变短。
                    </span>
                </div>
                <div class="field">
                    <label class="field-label">全局限速（KB/s，0 为不限）</label>
                    <input
                        class="input num"
                        type="number"
                        min="0"
                        value=${settings.globalRateLimitKBps}
                        onChange=${e => save({ globalRateLimitKBps: Number(e.currentTarget.value) })}
                    />
                </div>
                <div class="field">
                    <label class="field-label">单文件保留备份数</label>
                    <input
                        class="input num"
                        type="number"
                        min="0"
                        max="10"
                        value=${settings.keepBackups}
                        onChange=${e => save({ keepBackups: Number(e.currentTarget.value) })}
                    />
                </div>
            </div>

            <div class="card" style="margin-bottom:var(--space-4)">
                <h3 class="card-title">镜像加速</h3>
                <p class="text-sm text-dim" style="margin-top:0">
                    通过第三方代理下载可以绕过网络问题，但代理是中间人——它能看见你下载了什么。
                    本工具会校验 SHA-256，能发现内容被改，但代理仍能看到全部流量。
                </p>
                <div class="checkbox-row">
                    <input
                        type="checkbox"
                        id="mirror-enabled"
                        checked=${settings.mirrorEnabled}
                        onChange=${e => save({ mirrorEnabled: e.currentTarget.checked })}
                    />
                    <label for="mirror-enabled">启用镜像</label>
                </div>
                ${settings.mirrorEnabled && html`
                    <div class="field">
                        <label class="field-label">镜像前缀</label>
                        <input
                            class="input input--mono"
                            value=${settings.mirrorPrefix || ''}
                            onChange=${e => save({ mirrorPrefix: e.currentTarget.value })}
                        />
                        <span class="field-hint">
                            常用：${(settings.mirrorPresets || []).map(p => p.name).join('、')}
                        </span>
                    </div>
                `}
            </div>

            <div class="card" style="margin-bottom:var(--space-4)">
                <h3 class="card-title">启动</h3>
                <div class="checkbox-row">
                    <input
                        type="checkbox"
                        id="auto-start"
                        checked=${settings.autoStart}
                        disabled=${!settings.autoStartSupported}
                        onChange=${e => save({ autoStart: e.currentTarget.checked })}
                    />
                    <label for="auto-start">开机自动启动</label>
                </div>
                <p class="text-xs text-faint" style="margin:0">
                    ${settings.autoStartSupported
                        ? '写入当前用户的开机启动项，不需要管理员权限。移动程序目录后，下次启动会自动修正路径。'
                        : '当前以开发模式（java -jar）运行，没有固定的 exe，无法设置开机自启。请使用打包产物。'}
                </p>
            </div>

            <div class="card">
                <h3 class="card-title">关于</h3>
                <div class="kv">
                    <span class="kv-key">版本</span>
                    <span class="kv-val mono">${settings.version}</span>
                    <span class="kv-key">配置版本</span>
                    <span class="kv-val mono num">${settings.schemaVersion}</span>
                </div>
                <p class="text-xs text-faint" style="margin-bottom:0">
                    关闭浏览器标签页即可释放全部界面内存，后台服务不受影响。
                </p>
            </div>
        </div>
    `;
}
