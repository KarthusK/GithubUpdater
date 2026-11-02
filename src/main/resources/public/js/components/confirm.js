// @ts-check
/**
 * 应用内确认框，替换原生 `confirm()`。
 *
 * 为什么必须替换：原生 confirm 是浏览器 chrome 的一部分，它用的是
 * 操作系统的对话框风格，和界面完全不搭；而且它是**阻塞**的，样式无法控制，
 * 在 2026 年的桌面上是「网页套壳」最强的信号。
 *
 * 用法（异步、非阻塞）：
 *   if (await confirmAsync({ title: '删除条目', body: '…', confirmLabel: '删除', variant: 'danger' })) { … }
 *
 * 实现是模块级单例：同一时刻只可能有一个确认框，所以不需要 provider / context，
 * 一个 `<ConfirmHost />` 挂在根组件里即可。
 */

import { html } from 'htm/preact';
import { useState, useEffect } from 'preact/hooks';
import { Dialog } from './Dialog.js';

/** @typedef {{title:string, body:any, confirmLabel?:string, cancelLabel?:string, variant?:string}} ConfirmOptions */

/** @type {ConfirmOptions|null} */
let current = null;
/** @type {((v:boolean)=>void)|null} */
let resolver = null;
/** @type {Set<Function>} */
const subscribers = new Set();

function notify() {
    for (const fn of subscribers) fn();
}

/**
 * 弹一个确认框，等用户决定。
 *
 * @param {ConfirmOptions} opts
 * @returns {Promise<boolean>}
 */
export function confirmAsync(opts) {
    // 上一个还挂着就先按取消处理，避免 promise 永远不 resolve
    if (resolver) {
        const prev = resolver;
        resolver = null;
        prev(false);
    }

    current = opts;
    notify();

    return new Promise(resolve => {
        resolver = resolve;
    });
}

function settle(value) {
    const r = resolver;
    resolver = null;
    current = null;
    notify();
    if (r) r(value);
}

/**
 * 确认框宿主。挂在根组件里，全应用唯一。
 */
export function ConfirmHost() {
    const [, force] = useState(0);

    useEffect(() => {
        const fn = () => force(n => n + 1);
        subscribers.add(fn);
        return () => {
            subscribers.delete(fn);
        };
    }, []);

    if (!current) return null;

    const opts = current;

    return html`
        <${Dialog}
            title=${opts.title}
            onClose=${() => settle(false)}
            actions=${[
                { label: opts.cancelLabel || '取消', onClick: () => settle(false) },
                {
                    label: opts.confirmLabel || '确定',
                    variant: opts.variant || 'primary',
                    onClick: () => settle(true),
                },
            ]}
        >${opts.body}<//>
    `;
}
