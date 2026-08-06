// pages/Dashboard/components/BudgetPanel.jsx
//
// 사이드의 예산 위젯 — 총액·1인당, 희망 총 예산 스테퍼, 카테고리별 사용량 막대.
// 집계와 저장(디바운스)은 useBudget 훅이 소유하고, 여기서는 받은 값을 그린다.

import { HoldRepeatButton } from "./HoldRepeatButton";
import { won } from "../dashboardHelpers";

export function BudgetPanel({
  totalBudget,
  perPersonBudget,
  headcount,
  targetBudget,
  budgetDraft,
  setBudgetDraft,
  commitBudgetDraft,
  budgetEditCancelledRef,
  bumpTargetBudget,
  budgetPct,
  remainingBudget,
  budgetSegments,
}) {
  return (
    <div className="panel">
      {/* 사이드의 다른 카드(지도·검색)와 같은 자리에 같은 제목 줄을 둔다 —
          이 카드만 제목이 없으면 본문이 한 카드만 위에서 시작해 눈에 걸린다 */}
      <h4 className="panel-title">
        예산
        <span
          className="hint-ico"
          tabIndex={0}
          aria-label="예산 사용 안내"
          data-tip="블록마다 입력한 예산을 모두 더한 금액이에요. 희망 총 예산을 정하면 아래 막대가 얼마나 썼는지 보여줘요. 금액을 눌러 직접 입력할 수도 있어요."
        >
          ⓘ
        </span>
      </h4>

      <div className="bud-total">
        <span className="bud-total-label">총 </span>
        <span className="bud-total-value">{won(totalBudget) || "0원"}</span>
        {/* 정산은 결국 1인당 얼마인지가 궁금하다 — 총액 옆에 바로 붙인다 */}
        <span className="bud-total-per">
          1인당 {won(perPersonBudget) || "0원"}
          <span className="bud-total-per-n"> · {headcount}인</span>
        </span>
      </div>

      <div className="bud-target">
        <span>희망 총 예산</span>
        <div className="bud-stepper">
          <HoldRepeatButton onTrigger={() => bumpTargetBudget(-100000)}>
            -
          </HoldRepeatButton>
          {/* 금액을 누르면 직접 입력 — Enter/포커스 아웃으로 저장, Esc 취소 */}
          {budgetDraft === null ? (
            <button
              type="button"
              className="bud-stepper-value"
              title="클릭해서 직접 입력"
              onClick={() => setBudgetDraft(String(targetBudget))}
            >
              {targetBudget.toLocaleString()}원
            </button>
          ) : (
            <input
              className="bud-stepper-input"
              type="number"
              min="0"
              step="10000"
              autoFocus
              value={budgetDraft}
              onChange={(e) => setBudgetDraft(e.target.value)}
              onBlur={commitBudgetDraft}
              onKeyDown={(e) => {
                if (e.key === "Enter") e.currentTarget.blur();
                else if (e.key === "Escape") {
                  budgetEditCancelledRef.current = true;
                  e.currentTarget.blur();
                }
              }}
            />
          )}
          <HoldRepeatButton onTrigger={() => bumpTargetBudget(100000)}>
            +
          </HoldRepeatButton>
        </div>
      </div>

      {/* 블록별 사용량 — 한 덩어리 갈색 바 대신 블록마다 색이 다른 칸으로 쌓는다.
          칸에 마우스를 올리면 블록 이름·금액·비중이 툴팁으로 나온다. */}
      <div className="bud-track">
        {budgetSegments.map((seg) => (
          <div
            key={seg.cat}
            className="bud-seg"
            style={{
              width: `${seg.percent}%`,
              backgroundColor: seg.color,
            }}
            title={`${seg.name} · ${won(seg.cost)} (사용액의 ${Math.round(seg.shareOfTotal)}%)`}
          />
        ))}
        {budgetSegments.length === 0 && (
          <div className="bud-empty">아직 비용이 있는 블록이 없어요</div>
        )}
      </div>

      {budgetSegments.length > 0 && (
        <div className="bud-legend">
          {budgetSegments.map((seg) => (
            <span key={seg.cat} className="bud-legend-item">
              <i style={{ backgroundColor: seg.color }} />
              <b>{seg.name}</b>
              {won(seg.cost)}
              <em>{Math.round(seg.shareOfTotal)}%</em>
            </span>
          ))}
        </div>
      )}

      <div className="bud-foot">
        <span>희망 예산의 {Math.round(budgetPct)}% 사용</span>
        <span className={`bud-left ${remainingBudget < 0 ? "is-over" : ""}`}>
          {remainingBudget < 0
            ? `${won(Math.abs(remainingBudget))} 초과`
            : `남은 ${won(remainingBudget) || "0원"}`}
        </span>
      </div>
    </div>
  );
}
