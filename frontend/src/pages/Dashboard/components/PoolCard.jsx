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
  } = useSortable({ id, data: { from: "pool" } });
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
      <BlockCopyBadge onCopy={onCopy && (() => onCopy(id))} />
      <BlockLinkBadge item={item} />
    </div>
  );
}
