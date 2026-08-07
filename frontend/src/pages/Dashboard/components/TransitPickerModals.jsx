// pages/Dashboard/components/TransitPickerModals.jsx
//
// 교통 수단 선택 모달 3종. 세 모달이 같은 카드(TransitCandidateCard)와 같은
// 레이아웃(.transit-picker/.tp-*)을 쓰므로 한 파일에 둔다.
//   - 일괄 생성(tp-bulk): Day 전체 구간마다 수단을 고른다
//   - 단일 선택: 구간 버튼이 후보를 받아 오면 열린다 → 교통 블록 생성
//   - 재선택: 이미 만든 교통 블록의 수단을 바꾼다 → transportMeta 교체
//
// state·확정 콜백은 전부 부모(index.jsx)가 소유한다 — 이 컴포넌트는 그리기만 한다.
import Modal from "../../My/shared/ui/Modal";
import { TransitCandidateCard } from "./TransitCandidateCard";

export function TransitPickerModals({
  items,
  bulkTransitPicker,
  setBulkTransitPicker,
  setBulkChoice,
  confirmBulkTransit,
  transitPicker,
  setTransitPicker,
  setTransitPickerCandidate,
  confirmTransitChoice,
  transportReselectPicker,
  setTransportReselectPicker,
  setReselectCandidate,
  applyReselectTransport,
}) {
  return (
    <>
      {/* 이동수단 자동 생성(통합) — Day 전 구간의 후보를 한 모달에서 고르고
          "적용"하면 일괄 생성된다 (confirmBulkTransit) */}
      {bulkTransitPicker && (
        <Modal
          open
          onClose={() => setBulkTransitPicker(null)}
          overlayClassName="blk-modal-ov"
          bodyless
          closeOnBackdrop
        >
          <div className="transit-picker tp-bulk">
            <h3 className="tp-title">이동수단 자동 생성</h3>
            <p className="tp-route">
              구간마다 이동수단을 고르세요 — 기본값은 추천 수단이에요.
            </p>
            <div className="tp-seg-list">
              {bulkTransitPicker.segments.map((s) => {
                const pairKey = `${s.fromBlockId}-${s.toBlockId}`;
                const chosen = bulkTransitPicker.choices[pairKey];
                const routable = s.candidates?.some((c) => c.status === "OK");
                return (
                  <div key={pairKey} className="tp-seg">
                    <div className="tp-seg-route">
                      {items[s.fromBlockId]?.name ?? "?"} →{" "}
                      {items[s.toBlockId]?.name ?? "?"}
                      {!routable && (
                        <em className="tp-seg-none">경로 없음</em>
                      )}
                    </div>
                    {s.timetableApplied === false && s.timetableSkipReason && (
                      <p className="tp-banner tp-banner-warn">
                        {s.timetableSkipReason}
                      </p>
                    )}
                    {routable && (
                      <div className="tp-chips">
                        {s.candidates.map((c, idx) => (
                          <TransitCandidateCard
                            key={`${c.mode}-${idx}`}
                            candidate={c}
                            mode="select"
                            selected={chosen?.candidate === c}
                            onSelectCandidate={(cand) =>
                              setBulkChoice(pairKey, {
                                candidate: cand,
                                departure: cand.departures?.[0] ?? null,
                              })
                            }
                            selectedDepartureName={
                              chosen?.candidate === c
                                ? (chosen?.departure?.name ?? null)
                                : null
                            }
                            onSelectDeparture={(d) =>
                              setBulkChoice(pairKey, { candidate: c, departure: d })
                            }
                          />
                        ))}
                        <button
                          type="button"
                          className={`tp-chip tp-chip-skip ${chosen === null ? "on" : ""}`}
                          onClick={() => setBulkChoice(pairKey, null)}
                        >
                          제외
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <div className="tp-actions">
              <button
                type="button"
                className="tp-cancel"
                onClick={() => setBulkTransitPicker(null)}
              >
                취소
              </button>
              <button
                type="button"
                className="tp-apply"
                onClick={confirmBulkTransit}
              >
                {
                  Object.values(bulkTransitPicker.choices).filter(Boolean)
                    .length
                }
                개 구간 적용
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* 이동수단 선택 — 구간 버튼이 후보를 받아 오면 열린다. 고른 수단으로
          그 자리에 교통 블록이 생성된다 (confirmTransitChoice) */}
      {transitPicker && (
        <Modal
          open
          onClose={() => setTransitPicker(null)}
          overlayClassName="blk-modal-ov"
          bodyless
          closeOnBackdrop
        >
          <div className="transit-picker">
            <h3 className="tp-title">이동수단 선택</h3>
            <p className="tp-route">
              {items[transitPicker.currentId]?.name ?? "출발지"} →{" "}
              {items[transitPicker.nextId]?.name ?? "도착지"}
            </p>
            {transitPicker.segment?.timetableApplied === false &&
              transitPicker.segment?.timetableSkipReason && (
                <p className="tp-banner tp-banner-warn">
                  {transitPicker.segment.timetableSkipReason}
                </p>
              )}
            <div className="tp-list">
              {transitPicker.candidates.map((c, idx) => (
                <TransitCandidateCard
                  key={`${c.mode}-${idx}`}
                  candidate={c}
                  mode="select"
                  selected={transitPicker.chosenCandidate === c}
                  onSelectCandidate={setTransitPickerCandidate}
                  selectedDepartureName={
                    transitPicker.chosenCandidate === c
                      ? (transitPicker.chosenDeparture?.name ?? null)
                      : null
                  }
                  // 편을 고르면 그 편이 속한 후보도 함께 선택된다 — 선택 안 된
                  // 후보의 편을 바로 눌렀을 때 후보가 안 바뀌던 문제 방지
                  onSelectDeparture={(d) =>
                    setTransitPicker((prev) =>
                      prev
                        ? { ...prev, chosenCandidate: c, chosenDeparture: d }
                        : prev,
                    )
                  }
                />
              ))}
            </div>
            <div className="tp-actions">
              <button
                type="button"
                className="tp-cancel"
                onClick={() => setTransitPicker(null)}
              >
                취소
              </button>
              <button
                type="button"
                className="tp-apply"
                disabled={transitPicker.chosenCandidate?.status !== "OK"}
                onClick={confirmTransitChoice}
              >
                이 수단으로 추가
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* 교통 블록 편집 재선택 — 저장된 candidates 스냅샷으로 재조회 없이 연다.
          "저장"을 눌러야 PATCH /blocks/{id}/fields 로 transportMeta 를 통째 교체한다 */}
      {transportReselectPicker && (
        <Modal
          open
          onClose={() => setTransportReselectPicker(null)}
          overlayClassName="blk-modal-ov"
          bodyless
          closeOnBackdrop
        >
          <div className="transit-picker">
            <h3 className="tp-title">이동 수단 변경</h3>
            <div className="tp-list">
              {transportReselectPicker.candidates.map((c, idx) => (
                <TransitCandidateCard
                  key={`${c.mode}-${idx}`}
                  candidate={c}
                  mode="select"
                  selected={transportReselectPicker.chosenCandidate === c}
                  onSelectCandidate={setReselectCandidate}
                  selectedDepartureName={
                    transportReselectPicker.chosenCandidate === c
                      ? (transportReselectPicker.chosenDeparture?.name ?? null)
                      : null
                  }
                  // 편 선택 = 그 후보 선택까지 (단일 피커와 같은 이유)
                  onSelectDeparture={(d) =>
                    setTransportReselectPicker((prev) =>
                      prev
                        ? { ...prev, chosenCandidate: c, chosenDeparture: d }
                        : prev,
                    )
                  }
                />
              ))}
            </div>
            <div className="tp-actions">
              <button
                type="button"
                className="tp-cancel"
                onClick={() => setTransportReselectPicker(null)}
              >
                취소
              </button>
              <button
                type="button"
                className="tp-apply"
                disabled={transportReselectPicker.chosenCandidate?.status !== "OK"}
                onClick={applyReselectTransport}
              >
                저장
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
