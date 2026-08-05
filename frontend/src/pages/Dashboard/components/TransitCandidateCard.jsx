// pages/Dashboard/components/TransitCandidateCard.jsx
//
// 교통 후보 카드 — 편집 피커(mode="select")와 열람 접기(mode="view")가 공유한다.
// 렌더 로직은 하나, 상호작용만 분기한다(설계 3절).
// 시내(TRANSIT)는 legs/labels/transferCount/walkMeters, 시외(TRAIN/EXPRESS_BUS/AIR)는
// departures[] 인라인 확장을 그린다. 모든 신규 필드는 null이면 그 조각을 생략한다.

import "./TransitCandidateCard.css";
import { TRANSIT_MODE_META } from "../transitMeta";
import { isPerPersonMode } from "../dashboardHelpers";

const fareText = (fare, fareConfidence) => {
  if (fare == null) return null;
  if (fare === 0) return "무료";
  return `${fareConfidence === "ESTIMATE" ? "약 " : ""}${fare.toLocaleString()}원`;
};

const INTERCITY_MODES = new Set(["TRAIN", "EXPRESS_BUS", "AIR"]);

// 고를 수 없는 상태의 문구. 셋을 한 문구로 뭉개면 안 된다 — 사용자가 할 행동이 다르다.
// NO_ROUTE 는 몇 번을 다시 물어도 같은 답이라 재시도가 헛수고고, LOOKUP_FAILED 만
// 재시도가 유효하다. 예전에는 셋 다 "조회 실패"로 나가서 울릉도 사용자가 계속 재시도했다.
const STATUS_TEXT = {
  NO_SERVICE: "이 시각 이후 운행 없음",
  NO_ROUTE: "대중교통 경로가 없어요",
  LOOKUP_FAILED: "조회에 실패했어요",
};

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
      {/* 대기 시간을 보여주는 이유: 후보의 door-to-door 소요에 이 값이 들어 있는데
          내역이 안 보이면 "왜 12시간인가"를 설명할 수단이 없다 */}
      {departure.waitMin != null && (
        <span className="tcc-dep-wait">대기 {departure.waitMin}분</span>
      )}
      {departure.labels?.length > 0 && (
        <span className="tcc-dep-labels">
          {departure.labels.map((l) => (
            <em key={l}>{l}</em>
          ))}
        </span>
      )}
      {/* 환승 연결편. 이걸 감추면 사용자는 안동→목포 여정을 "KTX 708 12:01→14:11"
          두 시간짜리 기차로 오해한다 — 실제로는 수서에서 갈아타 21:47에 닿는다 */}
      {departure.connection && (
        <span className="tcc-dep-conn">
          ↳ {departure.connection.fromStation ?? "환승"} 환승
          {departure.connection.transferMin != null &&
            ` · 대기 ${departure.connection.transferMin}분`}
          {" · "}
          {departure.connection.name}
          {" "}
          {departure.connection.departureAt ?? "?"}
          {departure.connection.arrivalAt ? `→${departure.connection.arrivalAt}` : ""}
          {departure.connection.toStation ? ` · ${departure.connection.toStation} 도착` : ""}
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
        {!isOk && (
          <em className={`tcc-fail ${c.status === "LOOKUP_FAILED" ? "is-retryable" : ""}`}>
            {STATUS_TEXT[c.status] ?? "고를 수 없어요"}
          </em>
        )}
        {isOk && c.durationMin != null && (
          <span className="tcc-dur">{c.durationMin}분</span>
        )}
        {isOk && fareText(c.fare, c.fareConfidence) && (
          <span className="tcc-fare">
            {fareText(c.fare, c.fareConfidence)}
            {/* 표를 사람 수만큼 끊는 수단은 1인 요금이다 — 예산 패널 총액은
                인원만큼 곱해 들어가므로, 여기서 밝히지 않으면 안 맞아 보인다 */}
            {c.fare > 0 && isPerPersonMode(c.mode) && (
              <span className="cost-unit">/인</span>
            )}
          </span>
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

      {(c.transferCount != null ||
        c.walkMeters != null ||
        c.accessMin != null ||
        c.egressMin != null) && (
        <div className="tcc-sub">
          {c.transferCount != null && <span>환승 {c.transferCount}회</span>}
          {c.walkMeters != null && <span>도보 {c.walkMeters}m</span>}
          {/* 승차 지점까지 가는 시간·하차 지점에서 나오는 시간. 후보의 소요에 이미
              포함돼 있으므로, 안 보여주면 "역까지 가는 시간은 빠진 건가"를 알 수 없다 */}
          {c.accessMin != null && <span>승차지까지 {c.accessMin}분</span>}
          {c.egressMin != null && <span>하차 후 {c.egressMin}분</span>}
        </div>
      )}

      <LegsInline legs={c.legs} />

      {/* 출발편 선정 기준 시각 — 수단마다 탑승 여유가 달라 후보별로 다르다 */}
      {c.referenceAt && (
        <p className="tcc-banner">{c.referenceAt} 이후 출발편 기준</p>
      )}

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
