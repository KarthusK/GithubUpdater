// @ts-check
/**
 * 主题引导脚本。**非 module、非 defer**，必须在 `<head>` 里同步执行，
 * 早于 CSS 渲染，否则会看到一帧深色再跳到浅色。
 *
 * 只做一件事：把 localStorage 里的偏好写到 <html data-theme>。
 * 不读接口、不依赖任何模块（此刻还没有 importmap）。
 *
 * 合法值：'light' | 'dark'。其它任何值（含 'system'、空、损坏）
 * 都表示"跟随系统"——即删掉属性，交给 CSS 的 prefers-color-scheme。
 */
(function () {
    try {
        var t = localStorage.getItem('gu-theme');
        if (t === 'light' || t === 'dark') {
            document.documentElement.dataset.theme = t;
        }
    } catch (e) {
        // 隐私模式 / localStorage 被禁用：跟随系统即可，不影响使用
    }
})();
