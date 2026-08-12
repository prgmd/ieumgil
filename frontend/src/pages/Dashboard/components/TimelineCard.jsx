import { useDraggable } from "@dnd-kit/core";
import { catOf, fmtTime, PX } from "../dashboardHelpers";
import { CardBody } from "./CardBody";
import {
  BlockEditorBadge,
  BlockEditBadge,
  BlockCopyBadge,
  BlockLinkBadge,
} from "./BlockBadges";

export function TimelineCard({
  id,
  item,
  startMins,
  endMins,
  resizingState,
  onResizeStart,
  timelineStart,
  boundTop,
  onEditBlock,
  onCopy,
  lockedBy,
  editor,
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "timeline" },
  });
  const catStyle = catOf(item);
  const height = (item?.dur || 30) * PX;
  // 짧은 블록은 내용이 높이를 넘쳐 글이 잘린다 — 높이에 맞춰 단계적으로 접는다.
  // 전체 레이아웃(첫 줄+주소+소요)이 필요한 높이는 약 90px(패딩 20+줄 3개) —
  // 그보다 낮으면 아래 줄부터 순서대로 접는다. 잘린 채 그리는 구간이 없어야 한다.
  const sizeClass = [
    height < 92 && "hide-ctl", // "소요 n분" 줄부터 접는다
    height < 60 && "is-short", // 주소 줄까지
    height <= 34 && "is-tiny", // 한 줄 축약
  ]
    .filter(Boolean)
    .join(" ");
  const isThisResizing =
    resizingState?.id === id ? resizingState.direction : null;

  const handleEdgeClick = (e, direction) => {
    if (!resizingState) {
      e.stopPropagation();
      e.preventDefault();
      onResizeStart(id, direction, e.clientY, item.dur, startMins, boundTop);
    }
  };

  // 위치·높이는 시간 계산 결과라 인라인으로 남기고, 색·모양은 CSS(.slot/.card)가 쥔다.
  const topPx = (startMins - timelineStart) * PX;
  const slotStyle = {
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
    top: `${topPx}px`,
    height: `${height}px`,
  };

  return (
    <div
      className={`slot ${sizeClass} ${isDragging ? "is-dragging" : ""} ${isThisResizing ? "is-resizing" : ""}`}
      style={slotStyle}
    >
      <span className="tlab">{fmtTime(startMins)}</span>
      <span className="dot" />
      {/* 카드(.card)는 overflow:hidden 이라 모서리 배지는 slot 레벨에 둔다 */}
      <BlockEditorBadge editor={editor} />
      <div
        ref={setNodeRef}
        className={`card ${item?.auto ? "auto-block" : ""}`}
        {...(!isThisResizing ? attributes : {})}
        {...(!isThisResizing ? listeners : {})}
        // 💡 박스 전체 영역에 클릭 이벤트 연결 (드래그나 리사이즈 중이 아닐 때만 동작)
        onClick={() => {
          if (!isThisResizing && onEditBlock) {
            onEditBlock(id);
          }
        }}
      >
        <CardBody
          id={id}
          item={item}
          mode="timeline"
          startMins={startMins}
          endMins={endMins}
          isThisResizing={isThisResizing}
          // 자동 생성 교통 블록은 소요(door-to-door)가 길찾기 계산값이라 손으로
          // 리사이즈 못 하게 막는다 — 손잡이를 아예 안 그린다
          onEdge={
            item?.auto && item?.cat === "trans" ? undefined : handleEdgeClick
          }
          onEditBlock={onEditBlock}
          lockedBy={lockedBy}
        />
        {!isThisResizing && (
          <>
            <BlockEditBadge onEdit={onEditBlock && (() => onEditBlock(id))} />
            <BlockCopyBadge
              onCopy={onCopy && item?.cat !== "trans" && (() => onCopy(id))}
            />
            <BlockLinkBadge item={item} />
          </>
        )}
      </div>
    </div>
  );
}
