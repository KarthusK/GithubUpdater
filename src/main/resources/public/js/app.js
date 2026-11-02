// @ts-check
/** 根组件：视图切换、顶栏、全局副作用（SSE + 初始数据加载）。 */

import { html } from 'htm/preact';
import { useEffect, useCallback } from 'preact/hooks';
import { useStore } from './hooks/useStore.js';
import { useSse } from './hooks/useSse.js';
import { selectors, store } from './store.js';
import { api } from './api.js';
import { toastError } from './toast.js';
import { formatQuota } from './format.js';
import { ListView } from './views/ListView.js';
import { DetailView } from './views/DetailView.js';
import { WizardView } from './views/WizardView.js';
import { SettingsView } from './views/SettingsView.js';
import { ToastContainer } from './components/Toast.js';
import { Button } from './components/Button.js';

export function App() {
    const view = useStore(selectors.view);
    const quota = useStore(selectors.quota);
    const connectionStatus = useStore(selectors.connectionStatus);
    const wizardOpen = useStore(selectors.wizardOpen);

    useSse();

    // 首次加载：拉条目列表与配额。SSE 的 snapshot 只带任务，条目要走 REST。
    useEffect(() => {
        (async () => {
            try {
                const [entries, q] = await Promise.all([api.listEntries(), api.quota()]);
                store.setEntries(entries.entries || []);
                store.setQuota(q);
            } catch (e) {
                toastError(e);
            }
        })();
    }, []);

    const onOpenSettings = useCallback(() => store.navigate('settings'), []);
    const onBackToList = useCallback(() => store.navigate('list'), []);

    return html`
        <div class="app-shell">
            <header class="app-header">
                <h1 class="app-title" onClick=${onBackToList} style="cursor:pointer">
                    GithubUpdater
                </h1>
                <div class="app-header-spacer"></div>

                <${ConnectionDot} status=${connectionStatus} />

                ${quota && html`
                    <span class="text-xs text-dim num" title=${quota.authenticated ? '已认证' : '匿名访问'}>
                        ${formatQuota(quota)}
                    </span>
                `}

                <${Button} variant="ghost" small onClick=${onOpenSettings}>设置<//>
            </header>

            ${connectionStatus !== 'open' && html`
                <div class="conn-banner">
                    <span class="status-dot status-dot--warn"></span>
                    <span>
                        ${connectionStatus === 'connecting'
                            ? '正在连接事件流…'
                            : '事件流已断开，进度不会实时更新。正在自动重连…'}
                    </span>
                </div>
            `}

            <div class=${view === 'list' ? 'app-body app-body--flush' : 'app-body'}>
                ${view === 'list' && html`<${ListView} />`}
                ${view === 'detail' && html`<${DetailView} />`}
                ${view === 'settings' && html`<${SettingsView} />`}
            </div>

            ${wizardOpen && html`<${WizardView} />`}
            <${ToastContainer} />
        </div>
    `;
}

function ConnectionDot({ status }) {
    const cls = status === 'open'
        ? 'status-dot status-dot--ok'
        : status === 'error'
            ? 'status-dot status-dot--error'
            : 'status-dot status-dot--warn';
    const title = status === 'open' ? '事件流已连接'
        : status === 'connecting' ? '正在连接'
        : '事件流已断开';
    return html`<span class=${cls} title=${title}></span>`;
}
