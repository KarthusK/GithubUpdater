// @ts-check
/**
 * 入口。
 *
 * 零构建：浏览器原生 ESM + importmap（见 index.html）。
 * 不需要任何打包器，改完文件刷新即可。
 */

import { render, html } from 'htm/preact';
import { App } from './app.js';

// 页面错误收集。
//
// build/verify-ui.ps1 会读 window.__errors 断言"0 页面错误"。
// 这个数组必须在这里真正初始化并填充——历史上它只被读取、从未被赋值，
// 于是那条断言永远通过，等于没测。别再让它变成一条假断言。
/** @type {Array<{type:string, message:string, detail?:any}>} */
window.__errors = [];

window.addEventListener('error', e => {
    window.__errors.push({
        type: 'error',
        message: String(e.message || e.error || 'unknown'),
        detail: e.error && e.error.stack ? String(e.error.stack) : null,
    });
});

window.addEventListener('unhandledrejection', e => {
    const reason = e.reason;
    window.__errors.push({
        type: 'rejection',
        message: reason && reason.message ? String(reason.message) : String(reason),
        detail: reason && reason.stack ? String(reason.stack) : null,
    });
    console.error('未处理的 Promise 拒绝', reason);
});

const root = document.getElementById('app');

if (!root) {
    console.error('找不到 #app 挂载点');
} else {
    render(html`<${App} />`, root);
}
