// @ts-check
/**
 * 把 store 桥接进 Preact。
 *
 * selector 必须稳定（模块作用域或 useCallback），否则每次渲染都是新引用，
 * 会导致反复重订阅——这是本文件唯一的坑，也是 docs/05 里明确警告过的。
 */

import { useState, useEffect, useCallback } from 'preact/hooks';
import { store } from '../store.js';

/**
 * 订阅 store 的某个切片。
 *
 * @param {(s:any)=>any} selector 必须稳定引用
 * @returns {any}
 */
export function useStore(selector) {
    const [value, setValue] = useState(() => selector(store));

    useEffect(() => {
        // 用相等性判断避免无谓的重渲染
        const update = () => {
            const next = selector(store);
            setValue(prev => (prev === next ? prev : next));
        };
        const unsubscribe = store.subscribe(update);
        update();                     // 订阅时同步一次，防止错过变更
        return unsubscribe;
    }, [selector]);

    return value;
}

/**
 * 只读的 store 快照（不订阅）。
 * 用于事件回调里读最新值——回调闭包里的 store 引用本来就是最新的，
 * 这个函数只是让调用点更明确。
 */
export function readStore() {
    return store;
}

/**
 * 稳定的回调引用。
 * @template {Function} T
 * @param {T} fn
 * @returns {T}
 */
export function useStable(fn) {
    return useCallback(fn, []);
}
