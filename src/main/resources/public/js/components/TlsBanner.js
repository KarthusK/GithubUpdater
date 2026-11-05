// @ts-check
/**
 * 中间人代理诊断横幅。
 *
 * 只在**确认**有中间人时出现：条件是有 TLS 签发者，而不是"检查失败"。
 * 断网、404、限流同样会失败，把它们也说成"你被中间人劫持了"会制造恐慌，
 * 比不提示更糟——用户会去关一堆正常软件。
 *
 * 文案要回答三个问题，缺一个用户就卡住：
 *   1. 发生了什么（不是你的网络坏了，是本机有程序在替你解密 HTTPS）
 *   2. 是谁（签发者原文，可据此认出具体软件）
 *   3. 怎么修（关掉它，或在这里配它的 CA）
 */

import { html } from 'htm/preact';
import { isMitmFailure, issuerShortName } from '../format.js';
import { store } from '../store.js';
import { Button } from './Button.js';

/**
 * @param {object} props
 * @param {any} props.entry 条目（含 lastCheckStatus / lastTlsIssuer）
 * @param {any} props.customCa 自定义 CA 状态（来自设置接口）
 */
export function TlsBanner({ entry, customCa }) {
    if (!isMitmFailure(entry)) return null;

    // 已经配好 CA 且生效了就不必再喊——用户已经按提示处理过了
    if (customCa && customCa.active) return null;

    const issuer = entry.lastTlsIssuer;
    const short = issuerShortName(issuer);
    // 签发者出现在这里，说明它没有在本程序的信任链里。
    // 提示语里必须写清"配 CA"只是让本程序信任它，不是修好了系统级问题。

    return html`
        <div class="notice notice--warn" role="status">
            <div class="notice-main">
                <div class="notice-title">
                    检测到 TLS 中间人：${short || '未知签发者'}
                </div>
                <div class="notice-body">
                    本机有程序在解密 HTTPS（常见于加速器、抓包工具、企业安全网关）。
                    Java 不信任它的证书，所以检查无法完成。
                </div>
                <div class="notice-detail">
                    <span class="text-faint">证书签发者：</span>
                    <span class="mono">${issuer}</span>
                </div>
                <div class="notice-body">
                    关闭该软件即可恢复；或保存它的 CA 证书文件，只让本程序信任它。
                    ${customCa && customCa.requiresRestart
                        ? html`<span class="text-signal">配置已改，重启程序后生效。</span>`
                        : ''}
                </div>
            </div>
            <div class="notice-actions">
                <${Button} variant="primary" onClick=${() => store.openSettings()}>
                    去配置 CA
                <//>
            </div>
        </div>
    `;
}
