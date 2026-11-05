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

        case 'task.created':
            // 新建任务必须由服务端主动告知：task.state / task.progress 都只带
            // taskId，前端本地没有这个任务时无从渲染。缺了这条事件，用户点完
            // 下载后界面毫无反应，只能手动刷新才能看到进度。
            store.upsertTask({
                id: data.taskId,
                assetRuleId: data.assetRuleId,
                assetName: data.assetName,
                releaseTag: data.releaseTag,
                expectedSize: data.expectedSize,
                bytesDone: data.bytesDone || 0,
                state: data.state || 'QUEUED',
            });
            break;

        case 'task.progress':
            // 高频事件：不走 store 广播
            scheduleProgress(data);
            break;

        case 'task.state':
            store.setTaskState(data.taskId, data.state);
            if (['DONE', 'FAILED', 'CANCELLED', 'APPLIED'].includes(data.state)) {
                clearProgress(data.taskId);
                // 状态变了，任务的其它字段（如 errorMessage）也可能变了，重拉一次。
                // 用 store 上的共享实现，避免与 setTaskState 的补拉逻辑各写一份。
                store.fetchTask(data.taskId);
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
