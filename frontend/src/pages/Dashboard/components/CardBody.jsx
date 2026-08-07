import { fmtTime, won, fmtDur, catOf, isPerPersonFare } from "../dashboardHelpers";
import { transitRouteSummary } from "../transitMeta";

// 카드에 보일 소분류 — 교통 블록은 transportMeta.chosen.label 을 우선한다.
// 이동 수단 재선택은 transportMeta 만 바꿀 수 있어서(subCategory 는 생성 시 고정,
// LWW 미지원) sub 를 그대로 쓰면 재선택·새로고침 후 옛 수단명이 남는다.
const subLabelOf = (item) =>
  item?.cat === "trans"
    ? (item?.transportMeta?.chosen?.label ?? item?.sub)
    : item?.sub;

function CardBody({
  item,
  mode,
  startMins,
  endMins,
  isThisResizing,
  onEdge,
  lockedBy,
}) {
  const catStyle = catOf(item);
  // 교통 블록이면 경로 요약(노선·환승), 아니면 null
  const route = transitRouteSummary(item);

  if (mode !== "timeline") {
    return (
      <>
        <div className="l">
          <span className="cat">
            {catStyle.nm}
            {subLabelOf(item) ? ` · ${subLabelOf(item)}` : ""}
          </span>
          {lockedBy && <span className="lock-badge">✎ {lockedBy}</span>}
          <span className="grip">⠿</span>
        </div>
        {/* 💡 연필 아이콘 삭제, 글씨 두께만 강조 */}
        <div className="nm" title={item?.name}>
          {item?.name}
        </div>
        {/* 타임라인 카드와 같은 규칙 — 교통이면 경로, 아니면 메모·주소 */}
        <div className="sub">
          {route ? route.text : item?.detail || item?.address}
        </div>
      </>
    );
  }

  return (
    <>
      {onEdge && (
        <div
          className={`tl-edge is-top ${isThisResizing === "top" ? "is-active" : ""}`}
          onClick={(e) => onEdge(e, "top")}
        />
      )}
      <div className="l1">
        <span className="cat">
          {catStyle.nm}
          {subLabelOf(item) ? ` · ${subLabelOf(item)}` : ""}
        </span>
        {item?.auto && <span className="auto-badge">자동</span>}
        {lockedBy && <span className="lock-badge">✎ {lockedBy} 편집 중</span>}
        <span>
          <span className="nm">{item?.name}</span>{" "}
          <span className="nm-sub">{item?.detail}</span>
        </span>
        <span className="time">
          {fmtTime(startMins)} – {fmtTime(endMins)}
        </span>
        {/* 무료(0원)는 표기 자체를 생략한다 (QA 배치2).
            대중교통·기차·항공은 1인 요금이라 "/인"을 붙인다 — 안 붙이면 옆 패널의
            총액과 안 맞아 보인다(총액에는 인원만큼 곱해 들어간다). */}
        {item?.cost > 0 && (
          <span className="cost">
            {won(item.cost)}
            {isPerPersonFare(item) && <span className="cost-unit">/인</span>}
          </span>
        )}
      </div>
      {/* 교통 블록은 주소(출발지 좌표)보다 "어느 노선을 타고 어디서 갈아타는지"가
          훨씬 쓸모 있다 — 그 정보가 상세 모달에만 있어서 카드만 보고는 알 수 없었다.
          경로가 없는 블록(택시·구버전 등)은 기존대로 주소를 보여준다. */}
      {route ? (
        <div className="addr addr-route" title={route.text}>
          🚏 {route.text}
          {route.transfers != null && (
            <span className="addr-transfers">환승 {route.transfers}회</span>
          )}
        </div>
      ) : (
        <div className="addr">📍 {item?.address || "위치 정보 없음"}</div>
      )}
      <div className="ctl">
        {/* 리사이즈 중에는 안내 문구가 카테고리 색으로 강조된다(.dur.is-resizing) */}
        <span className={`dur ${isThisResizing ? "is-resizing" : ""}`}>
          {isThisResizing
            ? "마우스를 움직여 조절 후 클릭하여 확정"
            : `소요 ${fmtDur(item?.dur)}`}
        </span>
      </div>
      {onEdge && (
        <div
          className={`tl-edge is-bottom ${isThisResizing === "bottom" ? "is-active" : ""}`}
          onClick={(e) => onEdge(e, "bottom")}
        />
      )}
    </>
  );
}

export { CardBody };
