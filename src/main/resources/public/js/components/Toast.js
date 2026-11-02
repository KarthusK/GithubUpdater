// @ts-check
/** Toast 容器。挂在根组件上，全局唯一。 */

import { html } from 'htm/preact';
import { useState, useEffect } from 'preact/hooks';
import { listToasts, subscribeToasts, dismiss } from '../toast.js';

export function ToastContainer() {
    const [items, setItems] = useState(() => listToasts());

    useEffect(() => {
        const unsub = subscribeToasts(() => setItems(listToasts()));
        setItems(listToasts());
        return unsub;
    }, []);

    if (items.length === 0) return null;

    return html`
        <div class="toast-container" role="status" aria-live="polite">
            ${items.map(t => html`
                <div key=${t.id} class=${`toast toast--${t.type}`}>
                    <span class="toast-text">${t.text}</span>
                    <button
                        type="button"
                        class="toast-close"
                        title="关闭"
                        aria-label="关闭提示"
                        onClick=${() => dismiss(t.id)}
                    >×</button>
                </div>
            `)}
        </div>
    `;
}
