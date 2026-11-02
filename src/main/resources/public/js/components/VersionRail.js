// @ts-check
/**
 * 版本刻度 —— 这个界面的签名元素。
 *
 * 一条横线，两端各一个点：右端是"上游最新"，左端是"本地已应用"。
 * 落后时中间那段画成琥珀虚线，表示差的就是这一截。
 *
 * 几何上点必须**紧贴线的两端**，文字从点向外侧延伸——
 * 只有这样才能读成"刻度尺"，否则就是三段并排的文字。
 *
 * 关于 appliedTag：后端不返回它（Entry 只有 lastSeenTag），
 * 所以由调用方从任务记录里推导。推导不出来时显示"未知"而不是"未应用"——
 * 后者是一个断言，而这个界面此刻并不知道答案。
 */

import { html } from 'htm/preact';

/**
 * @param {object} props
 * @param {string|null|undefined} props.appliedTag 本地已应用的版本（未知时传空）
 * @param {string|null|undefined} props.upstreamTag 上游最新的版本
 * @param {boolean} props.hasUpdate 上游是否比已应用新
 */
export function VersionRail({ appliedTag, upstreamTag, hasUpdate }) {
    const applied = (appliedTag || '').trim();
    const upstream = (upstreamTag || '').trim();

    // 两个都没有：还没检查过，用一句话代替刻度
    if (!applied && !upstream) {
        return html`
            <div class="version-rail version-rail--empty">
                <span class="text-xs text-faint">
                    还没有检查过这个仓库，点「检查」获取上游版本
                </span>
            </div>
        `;
    }

    const pending = hasUpdate && !!applied;
    const appliedKnown = !!applied;

    return html`
        <div class="version-rail" role="group" aria-label="版本位置">
            <div class="version-rail-side version-rail-side--left">
                <span class="version-rail-label">已应用</span>
                <span class=${appliedKnown
                    ? 'version-rail-tag'
                    : 'version-rail-tag version-rail-tag--unknown'}>
                    ${appliedKnown ? applied : '未知'}
                </span>
            </div>

            <span class=${appliedKnown
                ? 'version-rail-dot version-rail-dot--filled'
                : 'version-rail-dot'}></span>

            <div class=${pending
                ? 'version-rail-track version-rail-track--gap'
                : 'version-rail-track'}></div>

            <span class=${pending
                ? 'version-rail-dot version-rail-dot--pending'
                : 'version-rail-dot version-rail-dot--filled'}></span>

            <div class="version-rail-side version-rail-side--right">
                <span class="version-rail-label">
                    ${pending ? '上游最新' : '最新'}
                </span>
                <span class=${pending
                    ? 'version-rail-tag version-rail-tag--pending'
                    : 'version-rail-tag'}>${upstream || '未知'}</span>
            </div>
        </div>
    `;
}
