// @ts-check
/**
 * 进度订阅通道。
 *
 * `task.progress` 事件可能每 200ms 一条/任务，2 个任务就是每秒 10 条。
 * 每条都走 store.emit() 会让整个列表重渲染，明显掉帧。
 *
 * 所以进度单独存一份，用 requestAnimationFrame 合并，且只有进度条组件订阅。
 * 这样滚动列表、切换视图都不会被进度更新拖累。
 */

/** @type {Map<number, {bytesDone:number, expectedSize:number, bytesPerSecond:number}>} */
const liveProgress = new Map();

/** @type {Set<Function>} 只有 ProgressBar 会订阅这里 */
const subscribers = new Set();

let rafId = null;

/**
 * 收到进度事件时调用。
 * @param {{taskId:number, bytesDone:number, expectedSize:number, bytesPerSecond:number}} data
 */
export function scheduleProgress(data) {
    liveProgress.set(data.taskId, data);

    // 已经在等下一帧了就不再排
    if (rafId !== null) return;

    rafId = requestAnimationFrame(() => {
        rafId = null;
        for (const fn of subscribers) {
            try {
                fn();
            } catch (e) {
                console.error('进度订阅者抛错', e);
            }
        }
    });
}

/**
 * 读取某个任务的实时进度。
 * @param {number} taskId
 */
export function getProgress(taskId) {
    return liveProgress.get(taskId) || null;
}

/**
 * 订阅进度刷新。
 * @param {Function} fn
 * @returns {Function} 取消订阅
 */
export function subscribeProgress(fn) {
    subscribers.add(fn);
    return () => subscribers.delete(fn);
}

/**
 * 任务结束时清掉它的实时进度，避免内存里留垃圾。
 * @param {number} taskId
 */
export function clearProgress(taskId) {
    liveProgress.delete(taskId);
    if (rafId !== null) {
        cancelAnimationFrame(rafId);
        rafId = null;
    }
}
