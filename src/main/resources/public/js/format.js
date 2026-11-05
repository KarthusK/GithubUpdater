// @ts-check
/** 格式化工具。全部输出中文语境的短文本，不用 Intl 的相对时间（它在本项目里过长）。 */

/**
 * 字节数转可读大小。
 * @param {number} bytes
 * @returns {string}
 */
export function formatBytes(bytes) {
    if (bytes === null || bytes === undefined || bytes < 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = bytes / 1024;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

/**
 * 速度。
 * @param {number} bps 字节每秒
 */
export function formatSpeed(bps) {
    if (!bps || bps <= 0) return '—';
    return `${formatBytes(bps)}/s`;
}

/**
 * 相对时间。刻意做得比 Intl.RelativeTimeFormat 更短。
 * @param {number|null} ms Unix 毫秒
 * @returns {string}
 */
export function formatRelative(ms) {
    if (!ms) return '从未';
    const diff = Date.now() - ms;
    if (diff < 0) return '刚刚';
    const sec = Math.floor(diff / 1000);
    if (sec < 60) return '刚刚';
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min} 分钟前`;
    const hour = Math.floor(min / 60);
    if (hour < 24) return `${hour} 小时前`;
    const day = Math.floor(hour / 24);
    if (day < 30) return `${day} 天前`;
    return formatDate(ms);
}

/**
 * 绝对时间，用于任务历史。
 * @param {number|null} ms
 */
export function formatDate(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
        + `${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 剩余配额的可读描述。
 *
 * `remaining` 为 -1 表示"还没发过任何 API 请求，配额未知"——这是 Quota.unknown()
 * 的占位值。界面上绝不能显示成 "-1/60"，那会让人以为配额是负的。
 * @param {any} quota
 */
export function formatQuota(quota) {
    if (!quota) return '配额未知';
    if (quota.remaining === null || quota.remaining === undefined || quota.remaining < 0) {
        return quota.authenticated ? '已认证' : '匿名';
    }
    if (quota.authenticated === false && quota.limit === 60) {
        return `匿名 ${quota.remaining}/${quota.limit}`;
    }
    return `${quota.remaining}/${quota.limit}`;
}

/**
 * 配额是否真的不足（未知时不判定为不足）。
 * @param {any} quota
 */
export function isQuotaLow(quota) {
    if (!quota || quota.remaining < 0) return false;
    return quota.remaining <= 5;
}

/**
 * 把剩余秒数转成"多久后重置"。
 * @param {number} seconds
 */
export function formatResetIn(seconds) {
    if (!seconds || seconds <= 0) return '即将重置';
    const min = Math.ceil(seconds / 60);
    if (min < 60) return `${min} 分钟后重置`;
    return `${Math.ceil(min / 60)} 小时后重置`;
}

/**
 * 任务状态的中文标签。
 * @param {string} state
 */
export function stateLabel(state) {
    switch (state) {
        case 'QUEUED': return '排队中';
        case 'RUNNING': return '下载中';
        case 'PAUSED': return '已暂停';
        case 'VERIFYING': return '校验中';
        case 'DONE': return '待应用';
        case 'APPLIED': return '已应用';
        case 'FAILED': return '失败';
        case 'CANCELLED': return '已取消';
        default: return state || '未知';
    }
}

/**
 * 任务状态的徽章样式类。
 * @param {string} state
 */
export function stateBadgeClass(state) {
    switch (state) {
        case 'DONE': return 'badge badge--ok';
        case 'APPLIED': return 'badge badge--ok';
        case 'RUNNING': return 'badge badge--running';
        case 'VERIFYING': return 'badge badge--running';
        case 'PAUSED': return 'badge badge--muted';
        case 'QUEUED': return 'badge badge--muted';
        case 'FAILED': return 'badge badge--error';
        case 'CANCELLED': return 'badge badge--muted';
        default: return 'badge badge--muted';
    }
}

/**
 * 资产类型的中文名。
 * @param {string} kind
 */
export function kindLabel(kind) {
    switch (kind) {
        case 'BINARY': return '单文件替换';
        case 'ARCHIVE': return '压缩包解压';
        case 'SOURCE': return '源码更新';
        case 'COLLECTION': return '仅归档';
        default: return kind || '未知';
    }
}

/**
 * 最近一次检查的结局。
 *
 * 与任务状态不同：这是"检查有没有跑通"，不是"文件有没有下好"。
 * 空串或 null 表示从未检查过。
 * @param {string} status
 */
export function checkStatusLabel(status) {
    switch (status) {
        case 'UPDATED': return '有新版本';
        case 'NO_CHANGE': return '已是最新';
        case 'ERROR': return '检查失败';
        case 'SKIPPED': return '已跳过';
        default: return '';
    }
}

/**
 * 这次检查失败是不是因为中间人劫持。
 *
 * 只有拿到签发者才算——仅仅是"失败"不够，那可能是断网、404、限流。
 * 把普通失败误报成"你被中间人攻击了"会制造恐慌，比不提示更糟。
 * @param {any} entry
 */
export function isMitmFailure(entry) {
    return !!(entry && entry.lastCheckStatus === 'ERROR' && entry.lastTlsIssuer);
}

/**
 * 从签发者字符串里抽出最容易认的部分。
 *
 * 完整 DN 是 `CN=steamcommunity.com, O=Steamcommunity302, C=CN`，
 * 直接铺在界面上太长，优先取 O（组织），没有就取 CN。
 * @param {string} issuer
 */
export function issuerShortName(issuer) {
    if (!issuer) return '';
    const o = /(?:^|,)\s*O=([^,]+)/.exec(issuer);
    if (o) return o[1].trim();
    const cn = /(?:^|,)\s*CN=([^,]+)/.exec(issuer);
    if (cn) return cn[1].trim();
    return issuer;
}

