// @ts-check
/**
 * 入口。
 *
 * 零构建：浏览器原生 ESM + importmap（见 index.html）。
 * 不需要任何打包器，改完文件刷新即可。
 */

import { render, html } from 'htm/preact';
import { App } from './app.js';

const root = document.getElementById('app');

if (!root) {
    console.error('找不到 #app 挂载点');
} else {
    render(html`<${App} />`, root);
}

// 未捕获的错误统一提示，避免界面"卡住但什么都不说"
window.addEventListener('unhandledrejection', e => {
    console.error('未处理的 Promise 拒绝', e.reason);
});
