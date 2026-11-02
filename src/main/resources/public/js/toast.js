// @ts-check
/**
 * Toast 通知。
 *
 * 独立于组件树——任何地方都能弹，不需要传 props。
 * 用模块级数组 + 订阅，避免把 Toast 塞进全局 store（它不是服务端数据）。
 */

/** @type {Array<{id:number, text:string, type:string}>} */
const toasts = [];
/** @type {Set<Function>} */
const subscribers = new Set();
let nextId = 1;

/** 默认停留时长。错误留久一点，用户需要时间读。 */
const DURATION = { ok: 2500, warn: 4000, error: 6000 };

/**
 * 弹一条提示。
 * @param {string} text
 * @param {'ok'|'warn'|'error'} [type]
 */
export function toast(text, type = 'ok') {
    const id = nextId++;
    toasts.push({ id, text, type });
    notify();

    const ms = DURATION[type] || 3000;
    setTimeout(() => dismiss(id), ms);
}

/**
 * 主动关掉一条。
 * @param {number} id
 */
export function dismiss(id) {
    const idx = toasts.findIndex(t => t.id === id);
    if (idx < 0) return;
    toasts.splice(idx, 1);
    notify();
}

/** @returns {Array<any>} 当前所有 toast（只读快照） */
export function listToasts() {
    return toasts.slice();
}

/**
 * @param {Function} fn
 * @returns {Function} 取消订阅
 */
export function subscribeToasts(fn) {
    subscribers.add(fn);
    return () => subscribers.delete(fn);
}

function notify() {
    for (const fn of subscribers) {
        try {
            fn();
        } catch (e) {
            console.error('toast 订阅者抛错', e);
        }
    }
}

/**
 * 把 API 错误统一转成一条提示。
 *
 * 特殊错误码要给出可操作的引导，而不是原样转发 message。
 * @param {any} err
 */
export function toastError(err) {
    const code = err && err.code;
    switch (code) {
        case 'NETWORK_ERROR':
            toast('无法连接后台服务，请确认程序仍在运行', 'error');
            return;
        case 'FILE_LOCKED': {
            const procs = (err.detail && err.detail.processes) || [];
            const names = procs.map(p => `${p.name}(${p.pid})`).join('、');
            toast(`目标文件被占用：${names || '未知进程'}`, 'warn');
            return;
        }
        case 'GIT_DIRTY':
            toast('工作副本有未提交改动，已中止。请手动处理后再试', 'warn');
            return;
        case 'RATE_LIMITED':
            toast('GitHub 配额已用尽，请等待重置或配置 Token', 'warn');
            return;
        case 'GITHUB_AUTH_FAILED':
            toast('GitHub Token 无效或已过期，请到设置页重新填写', 'error');
            return;
        case 'DIGEST_MISMATCH':
            toast('文件校验失败，已丢弃。请重试下载', 'error');
            return;
        case 'DIR_NOT_WRITABLE':
            toast('目标目录不可写，请换一个目录', 'error');
            return;
        default:
            toast((err && err.message) || '操作失败', 'error');
    }
}
