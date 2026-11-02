// @ts-check
/**
 * 右栏：设置。
 *
 * token 只写不读——服务端从不返回明文，界面也只显示"已配置/未配置"。
 *
 * 布局用「分节 + 设置行」而不是卡片：卡片一多，每个字段的边界都变成
 * 一个盒子，视觉重量全部相等，反而看不出分组。
 */

import { html } from 'htm/preact';
import { useState, useEffect, useCallback } from 'preact/hooks';
import { api } from '../api.js';
import { store, selectors } from '../store.js';
import { useStore } from '../hooks/useStore.js';
import { toast, toastError } from '../toast.js';
import { confirmAsync } from '../components/confirm.js';
import { Button } from '../components/Button.js';
import { formatQuota, formatResetIn } from '../format.js';

const THEMES = [
    { value: 'system', label: '跟随系统' },
    { value: 'light', label: '浅色' },
    { value: 'dark', label: '深色' },
];

export function SettingsView() {
    const [settings, setSettings] = useState(null);
    const [quota, setQuota] = useState(null);
    const [tokenInput, setTokenInput] = useState('');
    const [busy, setBusy] = useState(false);
    const theme = useStore(selectors.theme);

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

    const onClearToken = useCallback(async () => {
        const ok = await confirmAsync({
            title: '清除 GitHub Token？',
            body: html`
                <p>清除后将退回匿名访问，配额从每小时 5000 次降到 60 次。</p>
                <p class="text-xs text-faint" style="margin-top:var(--space-2)">
                    已跟踪的仓库不受影响，只是检查会更早碰到配额上限。
                </p>
            `,
            confirmLabel: '清除',
            variant: 'danger',
        });
        if (!ok) return;
        save({ githubToken: null });
    }, [save]);

    if (!settings) {
        return html`
            <div class="content-inner">
                <div class="content-head">
                    <div class="content-head-main">
                        <h1 class="content-title">设置</h1>
                    </div>
                </div>
                <p class="prose">加载中…</p>
            </div>
        `;
    }

    return html`
        <div class="content-inner">
            <div class="content-head">
                <div class="content-head-main">
                    <h1 class="content-title">设置</h1>
                    <div class="content-subtitle">
                        全部设置只保存在本机 <span class="mono">data/config.json</span>
                    </div>
                </div>
                <div class="content-head-actions">
                    <${Button} variant="ghost" onClick=${() => store.backToEntries()}>
                        返回条目
                    <//>
                </div>
            </div>

            <!-- ── GitHub 访问令牌 ───────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">GitHub 访问令牌</h2>
                    ${settings.githubTokenConfigured
                        ? html`<span class="badge badge--ok">已配置</span>`
                        : html`<span class="badge badge--muted">匿名访问</span>`}
                </div>
                <p class="settings-section-desc">
                    匿名访问每小时只有 60 次配额，配置 Token 后是 5000 次。
                    访问公开仓库只需要 <code>public_repo</code> 权限。
                    Token 不会以任何形式回传给界面。
                </p>
                <div class="field">
                    <label class="field-label" for="token-input">
                        ${settings.githubTokenConfigured ? '替换为新 Token' : '填写 Token'}
                    </label>
                    <div class="row">
                        <input
                            id="token-input"
                            class="input input--mono grow"
                            type="password"
                            placeholder=${settings.githubTokenConfigured
                                ? '已配置，填写新值可替换'
                                : 'ghp_xxxxxxxxxxxx'}
                            value=${tokenInput}
                            onInput=${e => setTokenInput(e.currentTarget.value)}
                        />
                        <${Button} variant="primary" disabled=${busy} onClick=${onSaveToken}>
                            保存
                        <//>
                        ${settings.githubTokenConfigured && html`
                            <${Button} variant="danger" disabled=${busy} onClick=${onClearToken}>
                                清除
                            <//>
                        `}
                    </div>
                </div>
            </section>

            <!-- ── 配额 ─────────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">API 配额</h2>
                    <span class="settings-section-meta text-xs text-faint">
                        ${quota && quota.authenticated ? '已认证' : '匿名'}
                    </span>
                </div>
                <div class="kv">
                    <span class="kv-key">剩余</span>
                    <span class="kv-val num">${formatQuota(quota)}</span>
                    <span class="kv-key">重置</span>
                    <span class="kv-val">${quota ? formatResetIn(quota.secondsUntilReset) : '—'}</span>
                </div>
                <p class="text-xs text-faint" style="margin-top:var(--space-3)">
                    只有「检查」会消耗配额。直连资产下载不消耗。
                </p>
            </section>

            <!-- ── 下载 ─────────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">下载</h2>
                </div>

                <div class="setting-row">
                    <div class="setting-row-label">
                        同时下载任务数
                        <span class="setting-row-hint">
                            默认 2。并发过高会互相抢带宽，总耗时不一定变短。
                        </span>
                    </div>
                    <div class="setting-row-control">
                        <input
                            class="input num"
                            type="number"
                            min="1"
                            max="8"
                            value=${settings.maxParallelTasks}
                            onChange=${e => save({ maxParallelTasks: Number(e.currentTarget.value) })}
                        />
                    </div>
                </div>

                <div class="setting-row">
                    <div class="setting-row-label">
                        全局限速
                        <span class="setting-row-hint">
                            KB/s，0 表示不限速。
                        </span>
                    </div>
                    <div class="setting-row-control">
                        <input
                            class="input num"
                            type="number"
                            min="0"
                            value=${settings.globalRateLimitKBps}
                            onChange=${e => save({ globalRateLimitKBps: Number(e.currentTarget.value) })}
                        />
                    </div>
                </div>

                <div class="setting-row">
                    <div class="setting-row-label">
                        单文件保留备份数
                        <span class="setting-row-hint">
                            替换前的文件会留在这个数字以内，用于回滚。0 表示不留。
                        </span>
                    </div>
                    <div class="setting-row-control">
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
            </section>

            <!-- ── 镜像加速 ─────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">镜像加速</h2>
                </div>
                <p class="settings-section-desc">
                    通过第三方代理下载可以绕过网络问题，但代理是中间人——
                    它能看见你下载了什么。本工具会校验 SHA-256，能发现内容被改，
                    但代理仍能看到全部流量。
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
                    <div class="field" style="margin-top:var(--space-3)">
                        <label class="field-label" for="mirror-prefix">镜像前缀</label>
                        <input
                            id="mirror-prefix"
                            class="input input--mono"
                            value=${settings.mirrorPrefix || ''}
                            onChange=${e => save({ mirrorPrefix: e.currentTarget.value })}
                        />
                        <span class="field-hint">
                            常用：${(settings.mirrorPresets || []).map(p => p.name).join('、')}
                        </span>
                    </div>
                `}
            </section>

            <!-- ── 启动 ─────────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">启动</h2>
                </div>
                <div class="checkbox-row">
                    <input
                        type="checkbox"
                        id="auto-start"
                        checked=${settings.autoStart}
                        disabled=${!settings.autoStartSupported}
                        onChange=${e => save({ autoStart: e.currentTarget.checked })}
                    />
                    <label for="auto-start">
                        开机自动启动
                        <span class="setting-row-hint">
                            ${settings.autoStartSupported
                                ? '写入当前用户的开机启动项，不需要管理员权限。移动程序目录后，下次启动会自动修正路径。'
                                : '当前以开发模式（java -jar）运行，没有固定的 exe，无法设置开机自启。请使用打包产物。'}
                        </span>
                    </label>
                </div>
            </section>

            <!-- ── 外观 ─────────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">外观</h2>
                </div>
                <div class="setting-row">
                    <div class="setting-row-label">
                        主题
                        <span class="setting-row-hint">
                            只影响这个面板，不影响后台服务。设置记在浏览器本地。
                        </span>
                    </div>
                    <div class="setting-row-control">
                        <div class="segmented" role="group" aria-label="主题">
                            ${THEMES.map(t => html`
                                <button
                                    key=${t.value}
                                    type="button"
                                    class="segmented-option"
                                    aria-pressed=${theme === t.value ? 'true' : 'false'}
                                    onClick=${() => store.setThemePref(t.value)}
                                >${t.label}</button>
                            `)}
                        </div>
                    </div>
                </div>
            </section>

            <!-- ── 关于 ─────────────────────────────────── -->
            <section class="settings-section">
                <div class="settings-section-head">
                    <h2 class="settings-section-title">关于</h2>
                </div>
                <div class="kv">
                    <span class="kv-key">版本</span>
                    <span class="kv-val mono">${settings.version}</span>
                    <span class="kv-key">配置版本</span>
                    <span class="kv-val mono num">${settings.schemaVersion}</span>
                </div>
                <p class="text-xs text-faint" style="margin-top:var(--space-3)">
                    关闭浏览器标签页即可释放全部界面内存，后台服务不受影响。
                </p>
            </section>
        </div>
    `;
}
