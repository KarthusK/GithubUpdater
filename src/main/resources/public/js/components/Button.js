// @ts-check
/** 通用按钮。所有状态（hover/active/disabled/focus）都在 CSS 里定义。 */

import { html } from 'htm/preact';

/**
 * @param {object} props
 * @param {'default'|'primary'|'danger'|'ghost'} [props.variant]
 * @param {boolean} [props.small]
 * @param {boolean} [props.disabled]
 * @param {()=>void} [props.onClick]
 * @param {any} [props.children]
 */
export function Button({ variant = 'default', small = false, disabled = false, onClick, children, ...rest }) {
    const cls = ['btn'];
    if (variant === 'primary') cls.push('btn--primary');
    if (variant === 'danger') cls.push('btn--danger');
    if (variant === 'ghost') cls.push('btn--ghost');
    if (small) cls.push('btn--sm');

    return html`
        <button
            class=${cls.join(' ')}
            disabled=${disabled}
            onClick=${onClick}
            ...${rest}
        >${children}</button>
    `;
}
