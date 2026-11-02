// @ts-check
/**
 * 主题运行时切换。
 *
 * `theme-boot.js` 负责首屏（避免闪烁），本模块负责运行期改主题。
 * 两者用同一个 localStorage 键 `gu-theme`，值域 `light|dark`；
 * 删除这个键表示跟随系统。
 */

const KEY = 'gu-theme';

/** @typedef {'system'|'light'|'dark'} ThemePref */

/**
 * 读取当前偏好。
 * @returns {ThemePref}
 */
export function getThemePref() {
    try {
        const v = localStorage.getItem(KEY);
        if (v === 'light' || v === 'dark') return v;
    } catch (e) {
        // 忽略：跟随系统
    }
    return 'system';
}

/**
 * 应用主题偏好。`system` 会删掉 data-theme，交回给 CSS 的媒体查询。
 * @param {ThemePref} pref
 */
export function setThemePref(pref) {
    try {
        if (pref === 'light' || pref === 'dark') {
            localStorage.setItem(KEY, pref);
            document.documentElement.dataset.theme = pref;
        } else {
            localStorage.removeItem(KEY);
            delete document.documentElement.dataset.theme;
        }
    } catch (e) {
        // 存储不可用时至少让本次会话生效
        if (pref === 'light' || pref === 'dark') {
            document.documentElement.dataset.theme = pref;
        } else {
            delete document.documentElement.dataset.theme;
        }
    }
}

/**
 * 读一个 CSS 长度令牌的数值（px）。
 *
 * 布局常量（行高、侧栏宽）在 JS 里硬编码会和 CSS 双写，改一处忘另一处就错位。
 * 统一从 `:root` 读，CSS 是唯一真相源。
 *
 * @param {string} name 令牌名，如 '--h-row'
 * @param {number} fallback 令牌缺失时的兜底值
 * @returns {number}
 */
export function readCssPx(name, fallback) {
    const raw = getComputedStyle(document.documentElement).getPropertyValue(name);
    const n = parseFloat(raw);
    return Number.isFinite(n) ? n : fallback;
}
