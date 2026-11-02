// @ts-check
/**
 * 状态栏。
 *
 * 原来这三样信息散在顶栏（连接点、配额、版本号都在右上角挤着）。
 * 收到这里的好处：顶栏只剩"我是谁 + 我要做什么"，状态信息不抢注意力，
 * 但需要时一眼就能扫到。
 *
 * 连接态用颜色 + 形态双编码（实心 = 已连接，空心 = 重连/断开），
 * 只有颜色的话色盲用户分不出差别。
 */

import { html } from 'htm/preact';
import { useStore } from '../hooks/useStore.js';
import { selectors } from '../store.js';
import { formatQuota, isQuotaLow } from '../format.js';

const MAX_DOWNLOADING = 99;

export function StatusBar() {
    const connectionStatus = useStore(selectors.connectionStatus);
    const quota = useStore(selectors.quota);
    const tasks = useStore(selectors.tasks);

    const activeCount = countActive(tasks);
    const conn = describeConnection(connectionStatus);
    const quotaLow = isQuotaLow(quota);

    return html`
        <footer class="app-statusbar">
            <span class="statusbar-item" title=${conn.title}>
                <span class=${`statusbar-dot ${conn.dotClass}`}></span>
                <span>${conn.text}</span>
            </span>

            <span class="statusbar-item statusbar-item--optional"
                  title=${quota && quota.authenticated ? '已认证' : '匿名访问'}>
                <span>配额</span>
                <span class=${quotaLow ? 'statusbar-num text-danger' : 'statusbar-num'}>
                    ${formatQuota(quota)}
                </span>
            </span>

            ${activeCount > 0 && html`
                <span class="statusbar-item statusbar-num">
                    ${activeCount} 个下载中
                </span>
            `}

            <span class="statusbar-spacer"></span>

            <span class="statusbar-item statusbar-item--optional">
                关闭此页不影响后台运行
            </span>
        </footer>
    `;
}

/** @param {Map<number, any>} tasks */
function countActive(tasks) {
    let n = 0;
    for (const t of tasks.values()) {
        if (t.state === 'RUNNING' || t.state === 'QUEUED' || t.state === 'VERIFYING') {
            n++;
            if (n >= MAX_DOWNLOADING) break;
        }
    }
    return n;
}

/** @param {string} status */
function describeConnection(status) {
    switch (status) {
        case 'open':
            return {
                text: '已连接',
                title: '事件流已连接，进度实时更新',
                dotClass: 'statusbar-dot--live',
            };
        case 'connecting':
            return {
                text: '正在连接…',
                title: '正在建立事件流连接',
                dotClass: 'statusbar-dot--warn',
            };
        default:
            return {
                text: '已断开，正在重连',
                title: '事件流已断开，进度不会实时更新。正在自动重连。',
                dotClass: 'statusbar-dot--error',
            };
    }
}
