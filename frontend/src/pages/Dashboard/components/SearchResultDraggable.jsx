import { useDraggable } from "@dnd-kit/core";

// 💡 새롭게 추가된 검색 결과용 드래그 컴포넌트
export function SearchResultDraggable({ place, onClick }) {
  const id = `search-result-${place.id}`;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "search", place },
  });

  return (
    <div
      ref={setNodeRef}
      className={`sr-item ${isDragging ? "is-dragging" : ""}`}
      {...attributes}
      {...listeners}
      onClick={() => onClick && onClick(place)}
    >
      <div className="sr-main">
        <div className="sr-dot">●</div>
        <div>
          <div className="sr-name">{place.place_name}</div>
          <div className="sr-addr">
            {place.road_address_name || place.address_name}
          </div>
          <div className="sr-cat">{place.category_group_name}</div>
        </div>
      </div>
      {/* 끌어다 놓기 유도용 손잡이 아이콘 */}
      <div className="sr-grip">⠿</div>
    </div>
  );
}
