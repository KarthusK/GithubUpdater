// @ts-check
/**
 * API 封装。
 *
 * 统一处理三件事：注入 token、解析 `{ok, data}` 信封、把错误码翻成可读信息。
 *
 * 后端所有响应都是 `{ok:true, data:{...}}` 或 `{ok:false, error:{code,message,detail}}`，
 * 这里把后者抛成带 `code` 的 Error，界面按 code 决定怎么提示。
 */

/** 服务端注入的 token。空字符串表示没读到（页面被外部保存过？）。 */
export const token = readToken();

function readToken() {
    const meta = document.querySelector('meta[name="api-token"]');
    return meta ? meta.getAttribute('content') || '' : '';
}

/** 带 code 的 API 错误。 */
export class ApiError extends Error {
    /**
     * @param {string} code 后端错误码，如 FILE_LOCKED
     * @param {string} message 人类可读信息
     * @param {any} detail 附加数据，如占用进程列表
     * @param {number} status HTTP 状态码
     */
    constructor(code, message, detail, status) {
        super(message);
        this.name = 'ApiError';
        this.code = code;
        this.detail = detail;
        this.status = status;
    }
}

/**
 * 发起一次 API 请求。
 *
 * @param {string} path 以 /api 开头
 * @param {object} [options]
 * @param {string} [options.method] 默认 GET
 * @param {any} [options.body] 会被 JSON 序列化
 * @returns {Promise<any>} 解包后的 data
 */
export async function request(path, options = {}) {
    const { method = 'GET', body } = options;
    /** @type {Record<string,string>} */
    const headers = { 'Authorization': `Bearer ${token}` };
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }

    let resp;
    try {
        resp = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (e) {
        // fetch 只在网络层失败时抛（服务没起、被防火墙拦）
        throw new ApiError('NETWORK_ERROR', '无法连接后台服务，请确认程序仍在运行', null, 0);
    }

    let payload = null;
    try {
        payload = await resp.json();
    } catch (e) {
        // 空响应体（如 204）或非 JSON
    }

    if (resp.ok && payload && payload.ok) {
        return payload.data;
    }

    const err = (payload && payload.error) || {};
    throw new ApiError(
        err.code || `HTTP_${resp.status}`,
        err.message || `请求失败（HTTP ${resp.status}）`,
        err.detail || null,
        resp.status,
    );
}

// ── 具体端点 ────────────────────────────────────────────

export const api = {
    health: () => request('/api/health'),
    quota: () => request('/api/quota'),

    listEntries: () => request('/api/entries'),
    createEntry: (repoUrl, channel) =>
        request('/api/entries', { method: 'POST', body: { repoUrl, channel } }),
    getEntry: (id) => request(`/api/entries/${id}`),
    deleteEntry: (id) => request(`/api/entries/${id}`, { method: 'DELETE' }),

    checkAll: () => request('/api/entries/check-all', { method: 'POST' }),
    checkOne: (id) => request(`/api/entries/${id}/check`, { method: 'POST' }),

    listRules: (entryId) => request(`/api/entries/${entryId}/rules`),
    createRule: (entryId, rule) =>
        request(`/api/entries/${entryId}/rules`, { method: 'POST', body: rule }),
    deleteRule: (ruleId) => request(`/api/rules/${ruleId}`, { method: 'DELETE' }),
    listBackups: (ruleId) => request(`/api/rules/${ruleId}/backups`),
    applySource: (ruleId, tag) =>
        request(`/api/rules/${ruleId}/apply-source`, { method: 'POST', body: { tag } }),

    listTasks: () => request('/api/tasks'),
    createTask: (task) => request('/api/tasks', { method: 'POST', body: task }),
    getTask: (id) => request(`/api/tasks/${id}`),
    pauseTask: (id) => request(`/api/tasks/${id}/pause`, { method: 'POST' }),
    resumeTask: (id) => request(`/api/tasks/${id}/resume`, { method: 'POST' }),
    cancelTask: (id) => request(`/api/tasks/${id}/cancel`, { method: 'POST' }),
    retryTask: (id) => request(`/api/tasks/${id}/retry`, { method: 'POST' }),

    applyTask: (id, opts) =>
        request(`/api/tasks/${id}/apply`, { method: 'POST', body: opts || {} }),
    rollbackTask: (id) => request(`/api/tasks/${id}/rollback`, { method: 'POST' }),
    rollbackBackup: (id) => request(`/api/backups/${id}/rollback`, { method: 'POST' }),

    getSettings: () => request('/api/settings'),
    patchSettings: (patch) =>
        request('/api/settings', { method: 'PATCH', body: patch }),
};
