// @ts-check
/**
 * 虚拟滚动容器。
 *
 * 两个关键决策（见 docs/05 第 7 节）：
 *
 * 1. **固定行高**。可变行高需要逐行测量，复杂度高一个量级；
 *    本界面行内容固定（名称、状态、时间、按钮），固定行高完全够用。
 *
 * 2. **焦点用 aria-activedescendant，不移动真实焦点**。
 *    真实焦点会随被销毁的行一起丢失到 body 上，恢复极其麻烦。
 *    aria-activedescendant 只要求"活跃行当前可见"，滚动出视野时浏览器不做任何事，
 *    屏幕阅读器同样支持——这是 WAI-ARIA 推荐的做法。
 */

import { html } from 'htm/preact';
import { useState, useRef, useEffect, useCallback } from 'preact/hooks';

/**
 * @param {object} props
 * @param {Array<any>} props.items
 * @param {number} props.rowHeight
 * @param {(item:any, index:number)=>any} props.renderRow
 * @param {number|string|null} props.activeId
 * @param {(id:any)=>void} [props.onActiveChange]
 * @param {number} [props.overscan]
 * @param {string} [props.emptyText]
 */
export function VirtualList({
    items,
    rowHeight,
    renderRow,
    activeId,
    onActiveChange,
    overscan = 4,
    emptyText = '暂无数据',
}) {
    const containerRef = useRef(null);
    const [scrollTop, setScrollTop] = useState(0);
    const [viewportHeight, setViewportHeight] = useState(0);

    // 容器尺寸变化不一定来自窗口 resize（侧边栏收起也会），所以用 ResizeObserver
    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        const ro = new ResizeObserver(() => setViewportHeight(el.clientHeight));
        ro.observe(el);
        setViewportHeight(el.clientHeight);
        return () => ro.disconnect();
    }, []);

    const totalHeight = items.length * rowHeight;
    const visibleCount = Math.ceil(viewportHeight / rowHeight) + overscan * 2;
    const startIndex = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
    const endIndex = Math.min(items.length, startIndex + visibleCount);
    const visible = items.slice(startIndex, endIndex);

    /** 让某一行进入视野。只有跨出视野时才真的滚动。 */
    const ensureVisible = useCallback(id => {
        const el = containerRef.current;
        if (!el) return;
        const idx = items.findIndex(i => i.id === id);
        if (idx < 0) return;

        const rowTop = idx * rowHeight;
        const rowBottom = rowTop + rowHeight;
        const viewTop = el.scrollTop;
        const viewBottom = viewTop + el.clientHeight;

        if (rowTop < viewTop) {
            el.scrollTop = rowTop;
        } else if (rowBottom > viewBottom) {
            el.scrollTop = rowBottom - el.clientHeight;
        }
    }, [items, rowHeight]);

    const handleKeyDown = e => {
        if (items.length === 0) return;
        const idx = items.findIndex(i => i.id === activeId);

        let next = -1;
        if (e.key === 'ArrowDown') next = Math.min(idx + 1, items.length - 1);
        else if (e.key === 'ArrowUp') next = Math.max(idx - 1, 0);
        else if (e.key === 'Home') next = 0;
        else if (e.key === 'End') next = items.length - 1;
        else return;

        e.preventDefault();
        const target = items[next];
        if (target && onActiveChange) {
            onActiveChange(target.id);
            ensureVisible(target.id);
        }
    };

    if (items.length === 0) {
        return html`<div class="empty-state"><p class="muted">${emptyText}</p></div>`;
    }

    return html`
        <div
            class="vlist"
            ref=${containerRef}
            tabindex="0"
            role="listbox"
            aria-activedescendant=${activeId != null ? `row-${activeId}` : undefined}
            onKeyDown=${handleKeyDown}
            onScroll=${e => setScrollTop(e.currentTarget.scrollTop)}
        >
            <div class="vlist-sizer" style=${`height:${totalHeight}px`}>
                <div class="vlist-window" style=${`transform:translateY(${startIndex * rowHeight}px)`}>
                    ${visible.map((item, i) => html`
                        <${Row}
                            key=${item.id}
                            id=${item.id}
                            index=${startIndex + i}
                            rowHeight=${rowHeight}
                            selected=${item.id === activeId}
                            renderRow=${renderRow}
                            item=${item}
                        />
                    `)}
                </div>
            </div>
        </div>
    `;
}

/**
 * 单行包装。key 在父层给，这里负责 id/aria/选中态。
 * @param {object} props
 */
function Row({ id, index, rowHeight, selected, renderRow, item }) {
    return html`
        <div
            id=${`row-${id}`}
            role="option"
            aria-selected=${selected ? 'true' : 'false'}
            style=${`height:${rowHeight}px`}
        >
            ${renderRow(item, index)}
        </div>
    `;
}
