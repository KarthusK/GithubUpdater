// @ts-check
/**
 * 进度条。
 *
 * 只订阅 progress.js 的独立通道，不碰全局 store。
 * 这是本项目的性能关键点：进度每秒 10 条，走 store 会让整个列表重渲染。
 */

import { html } from 'htm/preact';
import { useState, useEffect } from 'preact/hooks';
import { getProgress, subscribeProgress } from '../progress.js';
import { formatBytes, formatSpeed } from '../format.js';

/**
 * @param {object} props
 * @param {number} props.taskId
 * @param {number} props.expectedSize 快照里的总大小（实时进度没有时用它）
 * @param {number} props.bytesDone 快照里的已完成字节
 * @param {string} props.state 任务状态，用于决定颜色
 * @param {boolean} [props.compact] 紧凑模式：只显示百分比
 */
export function ProgressBar({ taskId, expectedSize, bytesDone, state, compact = false }) {
    const [live, setLive] = useState(() => getProgress(taskId));

    useEffect(() => {
        // taskId 变了要重新读一次，否则会显示上一个任务的进度
        setLive(getProgress(taskId));

        const unsub = subscribeProgress(() => {
            setLive(getProgress(taskId));
        });
        return unsub;
    }, [taskId]);

    const done = live ? live.bytesDone : bytesDone;
    const total = live && live.expectedSize ? live.expectedSize : expectedSize;
    const speed = live ? live.bytesPerSecond : 0;

    if (!total || total <= 0) {
        // 总大小未知（服务器没给 Content-Length）时无法算百分比
        return html`<div class="progress"><div class="progress-fill" style="width:0%"></div></div>`;
    }

    const pct = Math.min(100, (done / total) * 100);

    const cls = ['progress'];
    if (state === 'FAILED') cls.push('progress--error');
    if (state === 'PAUSED') cls.push('progress--paused');

    if (compact) {
        return html`
            <div class=${cls.join(' ')} title=${`${formatBytes(done)} / ${formatBytes(total)}`}>
                <div class="progress-fill" style="width:${pct}%"></div>
            </div>
        `;
    }

    return html`
        <div class="col" style="gap:var(--space-1)">
            <div class=${cls.join(' ')}>
                <div class="progress-fill" style="width:${pct}%"></div>
            </div>
            <div class="row text-xs text-faint num" style="justify-content:space-between">
                <span>${formatBytes(done)} / ${formatBytes(total)}</span>
                <span>${pct.toFixed(1)}%${speed > 0 ? ' · ' + formatSpeed(speed) : ''}</span>
            </div>
        </div>
    `;
}
