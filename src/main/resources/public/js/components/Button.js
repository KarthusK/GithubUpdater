// @ts-check
/** 通用按钮。所有状态（hover/active/disabled/focus）都在 CSS 里定义。 */

import { html } from 'htm/preact';

/**
 * @param {object} props
 * @param {'default'|'primary'|'danger'|'ghost'|'quiet'|'icon'} [props.variant]
 * @param {boolean} [props.small]
 * @param {boolean} [props.disabled]
 * @param {string} [props.title] 悬停提示；图标按钮必填（它是唯一的无障碍名称）
 * @param {string} [props.ariaLabel]
 * @param {()=>void} [props.onClick]
 * @param {any} [props.children]
 */
export function Button({
    variant = 'default',
    small = false,
    disabled = false,
    title,
    ariaLabel,
    onClick,
    children,
    ...rest
}) {
    const cls = ['btn'];
    if (variant === 'primary') cls.push('btn--primary');
    if (variant === 'danger') cls.push('btn--danger');
    if (variant === 'ghost') cls.push('btn--ghost');
    if (variant === 'quiet') cls.push('btn--quiet');
    if (variant === 'icon') cls.push('btn--icon');
    if (small) cls.push('btn--sm');

    return html`
        <button
            class=${cls.join(' ')}
            type="button"
            disabled=${disabled}
            title=${title}
            aria-label=${ariaLabel || title}
            onClick=${onClick}
            ...${rest}
        >${children}</button>
    `;
}
