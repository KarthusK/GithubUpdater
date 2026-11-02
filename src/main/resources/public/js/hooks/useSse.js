// @ts-check
/**
 * SSE 接入 hook。
 *
 * 事件到 store 的映射集中在这里，别散落到各组件——那样没人能回答
 * "这个字段是谁更新的"。
 */

import { useEffect } from 'preact/hooks';
import { createSse } from '../sse.js';
import { store } from '../store.js';
import { scheduleProgress, clearProgress } from '../progress.js';
import { token } from '../api.js';

export function useSse() {
    useEffect(() => {
        if (!token) return;

        const client = createSse({
            url: '/api/events',
            token,
            onEvent: handleEvent,
            onStatusChange: status => store.setConnectionStatus(status),
        });

        return () => client.close();
    }, []);
}

/**
 * @param {string} event
 * @param {any} data
 */
function handleEvent(event, data) {
    switch (event) {
        case 'snapshot':
            store.setTasks(data.tasks || []);
            if (data.quota) store.setQuota(data.quota);
            store.setChecksInProgress(!!data.checksInProgress);
            break;

        case 'task.progress':
            // 高频事件：不走 store 广播
            scheduleProgress(data);
            break;

        case 'task.state':
            store.setTaskState(data.taskId, data.state);
            if (['DONE', 'FAILED', 'CANCELLED', 'APPLIED'].includes(data.state)) {
                clearProgress(data.taskId);
                // 状态变了，任务的其它字段（如 errorMessage）也可能变了，重拉一次
                refreshTask(data.taskId);
            }
            break;

        case 'check.result':
            store.patchEntry(data.entryId, data);
            break;

        case 'check.done':
            store.setChecksInProgress(false);
            break;

        case 'check.started':
            store.setChecksInProgress(true);
            break;

        case 'quota.updated':
            store.setQuota(data);
            break;

        case 'error':
            store.setError(data);
            break;

        default:
            console.debug('未处理的 SSE 事件', event, data);
    }
}

/**
 * 单个任务重拉。失败静默——SSE 会再推。
 * @param {number} taskId
 */
async function refreshTask(taskId) {
    try {
        const { request } = await import('../api.js');
        const task = await request(`/api/tasks/${taskId}`);
        store.upsertTask(task);
    } catch (e) {
        // 忽略：任务可能已被清理
    }
}
