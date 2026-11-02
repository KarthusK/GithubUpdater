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
        <div class="toast-container">
            ${items.map(t => html`
                <div key=${t.id} class=${`toast toast--${t.type}`} onClick=${() => dismiss(t.id)}>
                    <span class="grow">${t.text}</span>
                </div>
            `)}
        </div>
    `;
}
