import { DragOverlay } from "@dnd-kit/core";
import { useBoardStore } from "../stores/useBoardStore";
import { catOf } from "../dashboardHelpers";
import { CardBody } from "./CardBody";

export function DragOverlayPreview({
  activeId,
  draggedItem,
  isDraggingFromPool,
  isDraggingFromSearch,
}) {
  const items = useBoardStore((s) => s.items);

  return (
    <DragOverlay>
      {activeId && draggedItem ? (
        isDraggingFromPool || isDraggingFromSearch ? (
          <div
            className="pcard is-overlay"
            style={{
              "--dc": catOf(draggedItem).hex,
              "--cb": catOf(draggedItem).bg,
            }}
          >
            <CardBody id={draggedItem.id} item={draggedItem} mode="pool" />
          </div>
        ) : (
          <div
            className="card is-overlay"
            style={{
              "--dc": catOf(draggedItem).hex,
              "--cb": catOf(draggedItem).bg,
            }}
          >
            <CardBody
              id={draggedItem.id}
              item={draggedItem}
              mode="timeline"
              startMins={items[activeId]?.startMins || 0}
              endMins={
                (items[activeId]?.startMins || 0) + (draggedItem.dur || 0)
              }
            />
          </div>
        )
      ) : null}
    </DragOverlay>
  );
}
