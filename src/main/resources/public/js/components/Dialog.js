// @ts-check
/** 对话框。破坏性操作必须走这里二次确认（见 docs/05 第 8.1 节）。 */

import { html } from 'htm/preact';
import { useEffect, useRef } from 'preact/hooks';
import { Button } from './Button.js';

/**
 * @param {object} props
 * @param {string} props.title
 * @param {any} props.children
 * @param {()=>void} props.onClose
 * @param {Array<{label:string, variant?:string, onClick:()=>void, disabled?:boolean}>} [props.actions]
 */
export function Dialog({ title, children, onClose, actions = [] }) {
    const backdropRef = useRef(null);
    const panelRef = useRef(null);

    useEffect(() => {
        // Esc 关闭
        const onKey = e => {
            if (e.key === 'Escape') onClose();
        };
        document.addEventListener('keydown', onKey);

        // 打开时把焦点移到弹窗里，否则 Tab 会跑到背后的页面上
        const prev = document.activeElement;
        if (panelRef.current) {
            panelRef.current.focus();
        }

        return () => {
            document.removeEventListener('keydown', onKey);
            if (prev instanceof HTMLElement) prev.focus();
        };
    }, [onClose]);

    // 点遮罩关闭，点内容不关（否则用户选文本时会误关）
    const onBackdropClick = e => {
        if (e.target === backdropRef.current) onClose();
    };

    return html`
        <div class="dialog-backdrop" ref=${backdropRef} onClick=${onBackdropClick}>
            <div
                class="dialog"
                role="dialog"
                aria-modal="true"
                aria-label=${title}
                tabindex="-1"
                ref=${panelRef}
            >
                <div class="dialog-header">${title}</div>
                <div class="dialog-body">${children}</div>
                <div class="dialog-footer">
                    ${actions.map(a => html`
                        <${Button}
                            key=${a.label}
                            variant=${a.variant || 'default'}
                            disabled=${!!a.disabled}
                            onClick=${a.onClick}
                        >${a.label}<//>
                    `)}
                </div>
            </div>
        </div>
    `;
}
