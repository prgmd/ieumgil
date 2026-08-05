// pages/Dashboard/components/TransitCandidateCard.jsx
//
// 교통 후보 카드 — 편집 피커(mode="select")와 열람 접기(mode="view")가 공유한다.
// 렌더 로직은 하나, 상호작용만 분기한다(설계 3절).
// 시내(TRANSIT)는 legs/labels/transferCount/walkMeters, 시외(TRAIN/EXPRESS_BUS/AIR)는
// departures[] 인라인 확장을 그린다. 모든 신규 필드는 null이면 그 조각을 생략한다.

import "./TransitCandidateCard.css";
import { TRANSIT_MODE_META } from "../transitMeta";

const fareText = (fare, fareConfidence) => {
  if (fare == null) return null;
  if (fare === 0) return "무료";
  return `${fareConfidence === "ESTIMATE" ? "약 " : ""}${fare.toLocaleString()}원`;
};

const INTERCITY_MODES = new Set(["TRAIN", "EXPRESS_BUS", "AIR"]);

function LegsInline({ legs }) {
  if (!legs || legs.length === 0) return null;
  return (
    <div className="tcc-legs">
      {legs.map((leg, i) => (
        <span key={i} className="tcc-leg">
          {leg.type === "WALK"
            ? `도보 ${leg.durationMin}분`
            : `${leg.lineName ? leg.lineName + " " : ""}${leg.from ?? "?"}→${leg.to ?? "?"} ${leg.durationMin}분`}
          {i < legs.length - 1 && <span className="tcc-leg-sep">·</span>}
        </span>
      ))}
    </div>
  );
}

function DepartureRow({ departure, selected, selectable, onSelect }) {
  const fare = departure.fareOptions
    ? `일반 ${fareText(departure.fareOptions.general, "CONFIRMED") ?? "-"}` +
      (departure.fareOptions.special ? ` · 특실 ${fareText(departure.fareOptions.special, "CONFIRMED")}` : "") +
      (departure.fareOptions.standing ? ` · 입석 ${fareText(departure.fareOptions.standing, "CONFIRMED")}` : "")
    : departure.fareConfidence === "UNKNOWN"
      ? "요금 별도 확인"
      : fareText(departure.fare, departure.fareConfidence);

  const Tag = selectable ? "button" : "div";
  return (
    <Tag
      type={selectable ? "button" : undefined}
      className={`tcc-departure ${selected ? "on" : ""}`}
      // 카드 루트 전체가 후보 선택 클릭을 받으므로(아래 참조), 편 클릭이
      // 루트로 번져 "편 선택 → 후보 재선택으로 편 초기화" 되지 않게 끊는다
      onClick={
        selectable
          ? (e) => {
              e.stopPropagation();
              onSelect(departure);
            }
          : undefined
      }
    >
      <span className="tcc-dep-name">
        {departure.name}
        {departure.grade && departure.grade !== departure.name && (
          <em className="tcc-dep-grade">{departure.grade}</em>
        )}
      </span>
      <span className="tcc-dep-time">
        {departure.departureAt ?? "?"}
        {departure.arrivalAt ? `→${departure.arrivalAt}` : ""}
      </span>
      {fare && <span className="tcc-dep-fare">{fare}</span>}
      {departure.labels?.length > 0 && (
        <span className="tcc-dep-labels">
          {departure.labels.map((l) => (
            <em key={l}>{l}</em>
          ))}
        </span>
      )}
    </Tag>
  );
}

/**
 * @param {object} candidate v2 Candidate(그대로)
 * @param {"select"|"view"} mode select=클릭 선택 가능, view=표시 전용
 * @param {boolean} [selected] 이 후보가 현재 선택됐는지(select 모드에서 카드 강조)
 * @param {(c:object)=>void} [onSelectCandidate] select 모드에서 카드 클릭 시
 * @param {string|null} [selectedDepartureName] 시외에서 현재 고른 편 이름
 * @param {(d:object)=>void} [onSelectDeparture] select 모드에서 편 클릭 시
 */
export function TransitCandidateCard({
  candidate: c,
  mode = "view",
  selected = false,
  onSelectCandidate,
  selectedDepartureName = null,
  onSelectDeparture,
}) {
  const meta = TRANSIT_MODE_META[c.mode] ?? { ico: "🚏", nm: c.mode };
  const selectable = mode === "select";
  const isIntercity = INTERCITY_MODES.has(c.mode);
  const isOk = c.status === "OK";
  const clickable = selectable && isOk;

  return (
    // 선택 모드에선 카드 전체가 클릭 범위다 — 머리줄만 버튼이면 라벨·경로 영역을
    // 누른 사용자가 "왜 선택이 안 되지"가 된다. 출발편 행만 자기 선택을 위해
    // 전파를 끊는다(DepartureRow 참조).
    <div
      className={`tcc ${selected ? "on" : ""} ${!isOk ? "is-unavailable" : ""} ${clickable ? "is-clickable" : ""}`}
      onClick={clickable ? () => onSelectCandidate?.(c) : undefined}
    >
      <button
        type="button"
        className="tcc-head"
        disabled={!selectable || !isOk}
        // 루트가 같은 선택을 처리한다 — 두 번 발화하지 않게 여기서 끊는다
        onClick={
          selectable
            ? (e) => {
                e.stopPropagation();
                onSelectCandidate?.(c);
              }
            : undefined
        }
      >
        <span className="tcc-ico">{meta.ico}</span>
        <span className="tcc-name">{c.label || meta.nm}</span>
        {!isOk && <em className="tcc-fail">조회 실패</em>}
        {isOk && c.durationMin != null && (
          <span className="tcc-dur">{c.durationMin}분</span>
        )}
        {isOk && fareText(c.fare, c.fareConfidence) && (
          <span className="tcc-fare">{fareText(c.fare, c.fareConfidence)}</span>
        )}
        {c.intervalMin != null && (
          <span className="tcc-interval">배차 ~{c.intervalMin}분</span>
        )}
      </button>

      {c.labels?.length > 0 && (
        <div className="tcc-labels">
          {c.labels.map((l) => (
            <em key={l}>{l}</em>
          ))}
        </div>
      )}

      {(c.transferCount != null || c.walkMeters != null) && (
        <div className="tcc-sub">
          {c.transferCount != null && <span>환승 {c.transferCount}회</span>}
          {c.walkMeters != null && <span>도보 {c.walkMeters}m</span>}
        </div>
      )}

      <LegsInline legs={c.legs} />

      {isIntercity && isOk && (() => {
        // view(열람) 모드는 확정된 편 하나만 보여준다 — 다른 편은 편집 모드 전용이다.
        // select(피커) 모드는 고르는 중이라 전체 목록을 그대로 보여준다.
        const departuresToShow =
          !selectable && selectedDepartureName
            ? (c.departures ?? []).filter((d) => d.name === selectedDepartureName)
            : c.departures;
        return departuresToShow && departuresToShow.length > 0 ? (
          <div className="tcc-departures">
            {departuresToShow.map((d) => (
              <DepartureRow
                key={d.name + d.departureAt}
                departure={d}
                selected={selectedDepartureName === d.name}
                selectable={selectable}
                onSelect={onSelectDeparture}
              />
            ))}
          </div>
        ) : (
          <p className="tcc-no-departure">기준 시각 이후 편이 없어요</p>
        );
      })()}
    </div>
  );
}
