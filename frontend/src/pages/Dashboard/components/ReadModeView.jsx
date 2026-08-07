import { Fragment } from "react";
import { TransitCandidateCard } from "./TransitCandidateCard";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";
import {
  won,
  catOf,
  dayDate,
  fmtTime,
  effectiveCostOf,
  blocksOfDay,
} from "../dashboardHelpers";
import {
  openBlockLink,
  hasExternalLink,
} from "../../../features/dashboard/api/externalLink";

// 앞뒤 일정 사이 시간 문제를 알린다 — 시간이 겹치거나(역전), 장소끼리 이동 블록
// 없이 빈 시간이 있으면 경고 문구를 돌려준다(문제 없으면 null).
function gapWarn(prev, cur) {
  if (!prev || !cur) return null;
  const prevEnd = (prev.startMins ?? 0) + (prev.dur ?? 0);
  const curStart = cur.startMins ?? 0;
  if (prevEnd > curStart) return "앞 일정과 시간이 겹쳐요";
  const gap = curStart - prevEnd;
  const bothPlace = prev.cat !== "trans" && cur.cat !== "trans";
  if (bothPlace && gap > 0) return `이동 정보가 없어요 · 빈 시간 ${gap}분`;
  return null;
}

export function ReadModeView({ board, items, dayKeys, project }) {
  // 배경·좌우 여백은 편집 모드와 같은 껍데기(.dash-shell/.dash-body)가 쥔다 —
  // 여기서 배경을 따로 칠하면 모드를 바꿀 때 화면이 갈라져 보인다.

  // Day별 비용 합계 (QA 배치2) — 편집 모드 예산과 같은 기준(보드 배치 블록만 +
  // 1인 요금 이동수단은 인원만큼 곱한다). 두 화면의 총액이 달라지면 안 된다.
  const headcount = Math.max(1, project?.budgetHeadcount || 1);
  const dayCostOf = (day) =>
    blocksOfDay(board, items, day).reduce(
      (sum, id) => sum + effectiveCostOf(items[id], headcount),
      0,
    );
  const totalCost = dayKeys.reduce((sum, day) => sum + dayCostOf(day), 0);

  return (
    <div className="dash-body read-view">
      <div className="rv-total">
        {/* 편집 모드와 헷갈리지 않게 모드를 명시한다 (QA 배치3) */}
        <span className="rv-mode-chip">📖 읽기 전용</span>
        여행 총 비용 <b>{won(totalCost) || "0원"}</b>
      </div>
      {dayKeys.map((day, index) => {
        // 보드 목록이 이미 오프셋 순이라 그 Day 만 걸러 내면 시각 순이다 —
        // 읽기 모드는 "하루의 흐름"이라 시간이 곧 순서여야 한다.
        const chain = blocksOfDay(board, items, day);
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
              {chain.map((id, i) => {
                const item = items[id];
                if (!item) return null;
                const startMins = item.startMins;
                const endMins = startMins + item.dur;
                const catStyle = catOf(item);
                const isTransport = item.cat === "trans" && item.transportMeta?.chosen;
                const warn = i > 0 ? gapWarn(items[chain[i - 1]], item) : null;
                return (
                  <Fragment key={id}>
                    {warn && <div className="rv-warn">⚠ {warn}</div>}
                    {/* 카테고리 색만 CSS 변수로 넘기고, 그 색을 어디에 쓸지는 CSS 가 정한다 */}
                    <div
                      className="rv-row"
                      style={{ "--dc": catStyle.hex, "--cb": catStyle.bg }}
                    >
                    <div className="rv-time">{fmtTime(startMins)}</div>
                    <div className="rv-dot" />
                    {isTransport ? (
                      // 읽기 모드는 "하루의 흐름을 훑는" 화면이다 — 어느 노선을
                      // 타고 어디서 갈아타는지가 접혀 있으면 그 목적을 못 채운다.
                      // 접기 없이 항상 펼쳐 둔다.
                      //
                      // 머리줄(아이콘·수단명·요금)을 따로 두지 않는다 —
                      // TransitCandidateCard 가 같은 것을 이미 그려서 아이콘과
                      // "대중교통"이 두 번 나왔다. 카드 하나만 남겨 통일한다.
                      <div className="rv-card rv-transit">
                        <TransitCandidateCard
                          candidate={item.transportMeta.chosen}
                          mode="view"
                          selectedDepartureName={
                            item.transportMeta.chosen.departureName ?? null
                          }
                        />
                      </div>
                    ) : (
                      <div className="rv-card">
                        <div className="rv-card-main">
                          <span
                            className="rv-badge"
                            title={`${catStyle.nm}${item.sub ? ` · ${item.sub}` : ""}`}
                          >
                            {catStyle.nm} {item.sub ? `· ${item.sub}` : ""}
                          </span>
                          <div>
                            {hasExternalLink(item) ? (
                              <button
                                type="button"
                                className="rv-name rv-name-link"
                                title="출처 링크 열기"
                                onClick={() => openBlockLink(item)}
                              >
                                {item.name}
                                <span className="rv-name-ico" aria-hidden="true">
                                  🔗
                                </span>
                              </button>
                            ) : (
                              <div className="rv-name">{item.name}</div>
                            )}
                            <div className="rv-addr">
                              📍 {item.address || "위치 정보 없음"}
                            </div>
                            {item.detail && (
                              <div className="rv-memo">📝 {item.detail}</div>
                            )}
                          </div>
                        </div>
                        <div className="rv-card-side">
                          {/* 자정을 넘으면 (+N일)을 붙인다 — fmtTime 은 하루 안 시각으로
                              접어 보여주므로, 표기가 없으면 23:30 - 05:00 이 하루 안에서
                              거꾸로 간 것처럼 읽힌다. 편집 모달과 같은 규칙이다. */}
                          <div className="rv-range">
                            {fmtTime(startMins)} - {fmtTime(endMins)}
                            {(() => {
                              const over =
                                blockApi.dayNoOfOffset(endMins) -
                                blockApi.dayNoOfOffset(startMins);
                              return over > 0 ? ` (+${over}일)` : "";
                            })()}
                          </div>
                          {item.cost > 0 && (
                            <div className="rv-cost">{won(item.cost)}</div>
                          )}
                        </div>
                      </div>
                    )}
                    </div>
                  </Fragment>
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
