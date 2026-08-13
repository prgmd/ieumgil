import { SortableContext, rectSortingStrategy } from "@dnd-kit/sortable";
import { useBoardStore } from "../stores/useBoardStore";
import { Select } from "../../../global/components/Select";
import { EmptyState } from "../../../global/components/EmptyState";
import { HintIcon } from "./HintIcon";
import { PoolCard } from "./PoolCard";

export function PoolPanel({
  dragPreview,
  poolFilterActive,
  visiblePool,
  poolCat,
  setPoolCat,
  poolQuery,
  setPoolQuery,
  handleCreateCustomBlock,
  openBlockDetail,
  handleCopyBlock,
  lockBadgeOf,
  editorBadgeOf,
  isDraggingFromPool,
  setPoolRef,
}) {
  const pool = useBoardStore((s) => s.pool);
  const items = useBoardStore((s) => s.items);

  return (
    <div
      className={`pool-sec ${dragPreview?.region === "pool" ? "dropover" : ""}`}
      ref={setPoolRef}
    >
      <div className="pool-head">
        <div className="pool-head-title">
          <b>후보 목록</b>{" "}
          <span className="n">
            {poolFilterActive
              ? `${visiblePool.length}/${pool.length}`
              : pool.length}
          </span>
          {/* 사용 안내는 ⓘ 커스텀 툴팁으로 (QA 배치2) — 호버 즉시,
              앱 디자인에 맞는 말풍선. 위 공간 부족하면 아래로 뒤집힘 */}
          <HintIcon
            label="후보 목록 사용 안내"
            tip="블록을 끌어다 놓아 보관하는 공간이에요. 타임라인·후보 목록 밖에 놓으면 삭제됩니다."
          />
        </div>
        {/* 대분류 필터 + 제목 검색 — 헤더에 함께 둔다. 렌더만 거른다 */}
        <div className="pool-tools">
          <div className="pool-filter-cat">
            <Select
              value={poolCat}
              onChange={setPoolCat}
              options={[
                { value: "ALL", label: "전체" },
                { value: "spot", label: "명소/활동" },
                { value: "food", label: "식당" },
                { value: "stay", label: "숙소" },
                { value: "trans", label: "교통" },
                { value: "etc", label: "기타" },
              ]}
            />
          </div>
          <input
            className="pool-search"
            type="text"
            placeholder="제목 검색"
            value={poolQuery}
            onChange={(e) => setPoolQuery(e.target.value)}
          />
          {poolFilterActive && (
            <button
              type="button"
              className="pool-filter-clear"
              onClick={() => {
                setPoolCat("ALL");
                setPoolQuery("");
              }}
              aria-label="필터 초기화"
            >
              ✕
            </button>
          )}
        </div>
        <button
          className="pool-add-btn"
          onClick={handleCreateCustomBlock}
        >
          + 커스텀 블록 만들기
        </button>
      </div>
      <div className="pool">
        <SortableContext
          items={visiblePool}
          strategy={rectSortingStrategy}
        >
          {visiblePool.map((id) => (
            <PoolCard
              key={id}
              id={id}
              item={items[id]}
              onEditBlock={openBlockDetail}
              onCopy={handleCopyBlock}
              lockedBy={lockBadgeOf(id)}
              editor={editorBadgeOf(id)}
            />
          ))}
        </SortableContext>
        {dragPreview?.region === "pool" && !isDraggingFromPool && (
          <div className="pool-dropzone" />
        )}
        {/* 빈 상태 — 어디서 채우는지(챗봇·지도 검색)를 함께 안내한다.
            드래그로 놓으려는 중에는 드롭존이 대신 보이므로 숨긴다 */}
        {pool.length === 0 && dragPreview?.region !== "pool" && (
          <div className="pool-empty">
            <EmptyState
              title="아직 보관한 블록이 없어요"
              desc={
                <>
                  오른쪽 <b>지도 검색</b>이나 <b>챗봇 이음이</b>의
                  추천을 끌어다 여기에 보관하고, <b>+ 커스텀 블록</b>
                  으로 직접 만들 수도 있어요.
                </>
              }
            />
          </div>
        )}
        {/* 블록은 있는데 필터·검색에 걸리는 게 없을 때 */}
        {pool.length > 0 &&
          visiblePool.length === 0 &&
          dragPreview?.region !== "pool" && (
            <div className="pool-noresult">
              <EmptyState
                title="조건에 맞는 블록이 없어요"
                desc="대분류나 검색어를 바꾸거나, 필터를 초기화해 다시 찾아보세요."
              />
            </div>
          )}
      </div>
    </div>
  );
}
