import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  BlockEditBadge,
  BlockLinkBadge,
  BlockCopyBadge,
  BlockEditorBadge,
} from "./BlockBadges";
import { CardBody } from "./CardBody";
import { catOf } from "../dashboardHelpers";

export function PoolCard({ id, item, onEditBlock, onCopy, lockedBy, editor }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
    // 드래그 시각은 DragOverlay 가 대신 그린다. 이때 dnd-kit 의 기본 layout
    // 애니메이션을 켜 두면 드롭 직후 소스 카드(특히 첫 카드)에 복귀 transform 이
    // 물린 채 안 지워져 카드가 어긋난 자리에 "벽돌"처럼 굳는다(다른 카드를
    // 움직여 리렌더되면 풀림). 오버레이가 있으니 이 애니메이션은 끈다.
  } = useSortable({
    id,
    data: { from: "pool" },
    animateLayoutChanges: () => false,
  });
  const catStyle = catOf(item);
  // dnd-kit 이 만들어주는 이동값과 카테고리 색만 인라인으로 넘긴다(색 지정은 CSS 몫).
  const style = {
    transform: isDragging ? undefined : CSS.Transform.toString(transform),
    transition,
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`pcard ${isDragging ? "is-dragging" : ""}`}
      data-pool-id={id}
      {...attributes}
      {...listeners}
      // 💡 박스 전체 영역에 클릭 이벤트 연결
      onClick={() => onEditBlock && onEditBlock(id)}
    >
      <BlockEditorBadge editor={editor} />
      <CardBody
        id={id}
        item={item}
        mode="pool"
        onEditBlock={onEditBlock}
        lockedBy={lockedBy}
      />
      <BlockEditBadge onEdit={onEditBlock && (() => onEditBlock(id))} />
      {/* 교통 블록은 구간에 묶여 복사 대상이 아니라 버튼을 숨긴다 */}
      <BlockCopyBadge
        onCopy={onCopy && item?.cat !== "trans" && (() => onCopy(id))}
      />
      <BlockLinkBadge item={item} />
    </div>
  );
}
