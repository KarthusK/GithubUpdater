// @ts-check
/** 根组件：外壳装配、视图切换、全局副作用（SSE + 初始数据加载 + 主题）。 */

import { html } from 'htm/preact';
import { useEffect, useCallback, useRef } from 'preact/hooks';
import { useStore } from './hooks/useStore.js';
import { useSse } from './hooks/useSse.js';
import { selectors, store } from './store.js';
import { api } from './api.js';
import { toast, toastError } from './toast.js';
import { setThemePref } from './theme.js';
import { ListView } from './views/ListView.js';
import { DetailView } from './views/DetailView.js';
import { WizardView } from './views/WizardView.js';
import { SettingsView } from './views/SettingsView.js';
import { StatusBar } from './components/StatusBar.js';
import { ToastContainer } from './components/Toast.js';
import { ConfirmHost } from './components/confirm.js';
import { Button } from './components/Button.js';

export function App() {
    const view = useStore(selectors.view);
    const connectionStatus = useStore(selectors.connectionStatus);
    const wizardOpen = useStore(selectors.wizardOpen);
    const theme = useStore(selectors.theme);

    useSse();

    // 首次加载：拉条目列表与配额。SSE 的 snapshot 只带任务，条目要走 REST。
    useEffect(() => {
        (async () => {
            try {
                const [entries, q] = await Promise.all([api.listEntries(), api.quota()]);
                const list = entries.entries || [];
                store.setEntries(list);
                store.setQuota(q);

                // 打开面板的第一眼就该是"最近在意的那个仓库有没有落后"。
                // 停在总览上等于让用户多点一次，而这次点击不提供任何信息。
                // 只在确实没有选中项时自动选，避免覆盖用户的选择。
                if (list.length > 0 && store.ui.view === 'entries' && store.ui.selectedEntryId == null) {
                    store.openEntry(list[0].id);
                }
            } catch (e) {
                toastError(e);
            }
        })();
    }, []);

    // 主题：store 是真相源，写到 <html> 与 localStorage
    useEffect(() => {
        setThemePref(theme);
    }, [theme]);

    useReconnectNotice(connectionStatus);

    const onOpenSettings = useCallback(() => store.openSettings(), []);
    const onOpenWizard = useCallback(() => store.setWizardOpen(true), []);
    // 点字标 = 回到条目总览（清选中、退出设置）
    const onHome = useCallback(() => store.closeEntry(), []);

    return html`
        <div class="app-shell">
            <header class="app-header">
                <span class="app-brand app-title" onClick=${onHome} title="回到条目总览">
                    GithubUpdater
                </span>
                <div class="app-header-spacer"></div>
                <${Button} variant="primary" onClick=${onOpenWizard}>添加仓库<//>
                <${Button}
                    variant="icon"
                    title="设置"
                    onClick=${onOpenSettings}
                >⚙<//>
            </header>

            <div class="app-split">
                <${ListView} />
                <main class="content">
                    ${view === 'settings'
                        ? html`<${SettingsView} />`
                        : html`<${DetailView} />`}
                </main>
            </div>

            <${StatusBar} />

            ${wizardOpen && html`<${WizardView} />`}
            <${ConfirmHost} />
            <${ToastContainer} />
        </div>
    `;
}

/**
 * 事件流断开时提示**一次**。
 *
 * 原来是用一条常驻的横幅，但它占掉一整行高度且长期挂着——连接正常时
 * 是纯噪音，断开时又太晚（用户已经在看别处）。改成：状态栏常驻真实状态，
 * 首次断开时补一条 toast。
 *
 * @param {string} status
 */
function useReconnectNotice(status) {
    const notifiedRef = useRef(false);

    useEffect(() => {
        if (status === 'open') {
            notifiedRef.current = false;
            return;
        }
        if (status !== 'error' && status !== 'closed') return;
        if (notifiedRef.current) return;

        notifiedRef.current = true;
        toast('与后台的事件流断开，进度不会实时更新。正在自动重连…', 'warn');
    }, [status]);
}
