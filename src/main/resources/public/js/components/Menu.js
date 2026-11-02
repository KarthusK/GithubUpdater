// @ts-check
/**
 * 下拉菜单。
 *
 * 用于详情页标题区的 `⋯`：把「停止跟踪」这类低频且有破坏性的动作
 * 收进菜单，让主界面只保留一个主操作。
 *
 * 关闭条件三样都要：点外部、按 Esc、点菜单项。
 * 少了任何一个，用户都会遇到"菜单关不掉"的窘境。
 */

import { html } from 'htm/preact';
import { useState, useRef, useEffect } from 'preact/hooks';

/**
 * @param {object} props
 * @param {any} props.trigger 触发按钮的内容（通常是 ⋯）
 * @param {string} props.label 无障碍名称
 * @param {Array<{label:string, onClick:()=>void, danger?:boolean, disabled?:boolean}>} props.items
 * @param {string} [props.separator] 在该 label 之前插入一条分隔线
 */
export function Menu({ trigger = '⋯', label = '更多操作', items, separator }) {
    const [open, setOpen] = useState(false);
    const anchorRef = useRef(null);

    useEffect(() => {
        if (!open) return;

        const onDocDown = e => {
            const el = anchorRef.current;
            if (el && !el.contains(/** @type {Node} */ (e.target))) {
                setOpen(false);
            }
        };
        const onKey = e => {
            if (e.key === 'Escape') {
                e.stopPropagation();
                setOpen(false);
            }
        };

        // 用捕获阶段：页面里的其它 click 处理器调用 stopPropagation 也不影响关闭
        document.addEventListener('mousedown', onDocDown, true);
        document.addEventListener('keydown', onKey);
        return () => {
            document.removeEventListener('mousedown', onDocDown, true);
            document.removeEventListener('keydown', onKey);
        };
    }, [open]);

    return html`
        <div class="menu-anchor" ref=${anchorRef}>
            <button
                type="button"
                class="btn btn--icon"
                title=${label}
                aria-label=${label}
                aria-haspopup="menu"
                aria-expanded=${open ? 'true' : 'false'}
                onClick=${() => setOpen(v => !v)}
            >${trigger}</button>

            ${open && html`
                <div class="menu" role="menu">
                    ${items.map((it, i) => html`
                        ${separator === it.label && html`<div class="menu-sep" role="none"></div>`}
                        <button
                            key=${it.label + i}
                            type="button"
                            role="menuitem"
                            class=${it.danger ? 'menu-item menu-item--danger' : 'menu-item'}
                            disabled=${!!it.disabled}
                            onClick=${() => {
                                setOpen(false);
                                it.onClick();
                            }}
                        >${it.label}</button>
                    `)}
                </div>
            `}
        </div>
    `;
}
