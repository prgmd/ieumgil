import { useState } from "react";
import { TransitCandidateCard } from "./TransitCandidateCard";
import { TRANSIT_MODE_META } from "../transitMeta";
import {
  won,
  catOf,
  dayDate,
  fmtTime,
  effectiveCostOf,
  isPerPersonFare,
} from "../dashboardHelpers";

export function ReadModeView({ chains, items, dayKeys, project }) {
  // 배경·좌우 여백은 편집 모드와 같은 껍데기(.dash-shell/.dash-body)가 쥔다 —
  // 여기서 배경을 따로 칠하면 모드를 바꿀 때 화면이 갈라져 보인다.

  // Day별 비용 합계 (QA 배치2) — 편집 모드 예산과 같은 기준(체인 배치 블록만 +
  // 1인 요금 이동수단은 인원만큼 곱한다). 두 화면의 총액이 달라지면 안 된다.
  const headcount = Math.max(1, project?.budgetHeadcount || 1);
  const dayCostOf = (day) =>
    (chains[day] || []).reduce(
      (sum, id) => sum + effectiveCostOf(items[id], headcount),
      0,
    );
  const totalCost = dayKeys.reduce((sum, day) => sum + dayCostOf(day), 0);

  // 교통 블록 접기 카드 — 행(block)별 펼침 상태. Day 전체가 아니라 블록별로 접는다.
  const [expandedIds, setExpandedIds] = useState(() => new Set());
  const toggleExpanded = (id) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    <div className="dash-body read-view">
      <div className="rv-total">
        {/* 편집 모드와 헷갈리지 않게 모드를 명시한다 (QA 배치3) */}
        <span className="rv-mode-chip">📖 읽기 전용</span>
        여행 총 비용 <b>{won(totalCost) || "0원"}</b>
      </div>
      {dayKeys.map((day, index) => {
        // 표시는 시각 순으로 (QA ⓒ) — 체인은 orderKey 순서라 드래그·수정 이력에
        // 따라 시각 순서와 어긋날 수 있는데, 읽기 모드는 "하루의 흐름"이라
        // 시간이 곧 순서여야 한다. 정렬은 표시 전용이라 데이터를 건드리지 않는다.
        const chain = [...(chains[day] || [])].sort(
          (a, b) => (items[a]?.startMins ?? 0) - (items[b]?.startMins ?? 0),
        );
        const dayCost = dayCostOf(day);
        return (
          <div key={day} className="rv-day">
            <h2 className="rv-day-title">
              Day {index + 1}{" "}
              <span className="rv-day-date">
                {dayDate(project, index) || "날짜 미정"}
              </span>
              {dayCost > 0 && (
                <span className="rv-day-cost">{won(dayCost)}</span>
              )}
            </h2>

            {/* 빈 Day 도 생략하지 않는다(RO-02) — 건너뛰면 Day 번호가 끊겨
                "아직 안 짠 날"과 "없는 날"을 구분할 수 없다 */}
            {chain.length === 0 ? (
              <p className="rv-day-empty">일정 없음</p>
            ) : (
            <div className="rv-list">
              <div className="rv-line" />
              {chain.map((id) => {
                const item = items[id];
                if (!item) return null;
                const startMins = item.startMins;
                const endMins = startMins + item.dur;
                const catStyle = catOf(item);
                const isTransport = item.cat === "trans" && item.transportMeta?.chosen;
                return (
                  // 카테고리 색만 CSS 변수로 넘기고, 그 색을 어디에 쓸지는 CSS 가 정한다
                  <div
                    key={id}
                    className="rv-row"
                    style={{ "--dc": catStyle.hex, "--cb": catStyle.bg }}
                  >
                    <div className="rv-time">{fmtTime(startMins)}</div>
                    <div className="rv-dot" />
                    {isTransport ? (
                      <div className="rv-card rv-transit">
                        <button
                          type="button"
                          className="rv-transit-head"
                          onClick={() => toggleExpanded(id)}
                        >
                          <span className="tcc-ico">
                            {TRANSIT_MODE_META[item.transportMeta.chosen.mode]?.ico ??
                              "🚏"}
                          </span>
                          <b>{item.transportMeta.chosen.label ?? item.name}</b>
                          {item.transportMeta.chosen.departureName && (
                            <span> {item.transportMeta.chosen.departureName}</span>
                          )}
                          {item.cost > 0 && (
                            <span className="rv-cost">
                              {won(item.cost)}
                              {isPerPersonFare(item) && (
                                <span className="cost-unit">/인</span>
                              )}
                            </span>
                          )}
                          <span className="rv-transit-caret">
                            {expandedIds.has(id) ? "▲" : "▼"}
                          </span>
                        </button>
                        {expandedIds.has(id) && (
                          <div className="rv-transit-body">
                            <TransitCandidateCard
                              candidate={item.transportMeta.chosen}
                              mode="view"
                              selectedDepartureName={
                                item.transportMeta.chosen.departureName ?? null
                              }
                            />
                            {item.transportMeta.segment?.referenceAt && (
                              <p className="tp-banner">
                                기준: {item.transportMeta.segment.referenceAt} 이후
                                출발
                              </p>
                            )}
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="rv-card">
                        <div className="rv-card-main">
                          <span className="rv-badge">
                            {catStyle.nm} {item.sub ? `· ${item.sub}` : ""}
                          </span>
                          <div>
                            <div className="rv-name">{item.name}</div>
                            <div className="rv-addr">
                              📍 {item.address || "위치 정보 없음"}
                            </div>
                          </div>
                        </div>
                        <div className="rv-card-side">
                          <div className="rv-range">
                            {fmtTime(startMins)} - {fmtTime(endMins)}
                          </div>
                          {item.cost > 0 && (
                            <div className="rv-cost">{won(item.cost)}</div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
