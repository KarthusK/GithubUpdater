// @ts-check
/**
 * SSE 客户端。
 *
 * **不能用原生 EventSource**：它重连时不带自定义请求头，而我们的接口要求
 * `Authorization: Bearer`。把 token 放 URL query 会进浏览器历史、代理日志、
 * Referer 头——不可接受。
 *
 * 所以用 fetch + ReadableStream 自己解析 SSE 帧，代价约 100 行。
 */

/**
 * 创建 SSE 客户端。
 *
 * @param {object} opts
 * @param {string} opts.url
 * @param {string} opts.token
 * @param {(event:string, data:any)=>void} opts.onEvent
 * @param {(status:string)=>void} opts.onStatusChange connecting|open|closed|error
 * @returns {{close: ()=>void}}
 */
export function createSse({ url, token, onEvent, onStatusChange }) {
    /** @type {AbortController|null} */
    let controller = null;
    let retryDelay = 1000;
    let stopped = false;

    async function run() {
        while (!stopped) {
            controller = new AbortController();
            try {
                onStatusChange('connecting');
                const resp = await fetch(url, {
                    headers: {
                        'Authorization': `Bearer ${token}`,
                        'Accept': 'text/event-stream',
                    },
                    signal: controller.signal,
                    // 关掉浏览器缓存，否则可能拿到上一次的流
                    cache: 'no-store',
                });

                if (!resp.ok) {
                    throw new Error(`SSE 连接失败：HTTP ${resp.status}`);
                }

                onStatusChange('open');
                retryDelay = 1000;              // 连上了就重置退避
                await readStream(resp.body, onEvent);
                onStatusChange('closed');       // 服务端正常关闭
            } catch (e) {
                if (stopped || (e && e.name === 'AbortError')) return;
                onStatusChange('error');
            }

            if (stopped) return;
            await sleep(retryDelay);
            retryDelay = Math.min(retryDelay * 2, 30000);
        }
    }

    /**
     * @param {ReadableStream<Uint8Array>} body
     * @param {(event:string, data:any)=>void} onEvent
     */
    async function readStream(body, onEvent) {
        const reader = body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            // SSE 帧以空行分隔
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
                const frame = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 2);
                parseFrame(frame, onEvent);
            }
        }
    }

    /**
     * @param {string} frame
     * @param {(event:string, data:any)=>void} onEvent
     */
    function parseFrame(frame, onEvent) {
        let event = 'message';
        /** @type {string[]} */
        const dataLines = [];

        for (const line of frame.split('\n')) {
            if (line.startsWith(':')) continue;             // 注释行（keepalive）
            if (line.startsWith('event:')) event = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }

        if (dataLines.length === 0) return;
        try {
            onEvent(event, JSON.parse(dataLines.join('\n')));
        } catch (e) {
            console.warn('SSE 帧解析失败', e, frame);
        }
    }

    const sleep = ms => new Promise(r => setTimeout(r, ms));

    run();

    return {
        close() {
            stopped = true;
            if (controller) controller.abort();
        },
    };
}
