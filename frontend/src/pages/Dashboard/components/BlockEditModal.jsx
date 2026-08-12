import { useBoardStore } from "../stores/useBoardStore";
import Modal from "../../My/shared/ui/Modal";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";
import { fmtTime, isTempId } from "../dashboardHelpers";
import { BlockEditForm } from "./BlockEditForm";

export function BlockEditModal({
  pinPickMode,
  handleCancelEdit,
  lockBadgeOf,
  pinnedLocation,
  handleRequestPinPick,
  handleSaveBlock,
  handleReselectTransport,
}) {
  const editingBlockId = useBoardStore((s) => s.editingBlockId);
  const items = useBoardStore((s) => s.items);

  if (!editingBlockId || !items[editingBlockId]) return null;

  return (
    // 공유 Modal 로 열기·Esc·백드롭 정책을 통일하되, 겉모습은 기존 blk-modal-ov
    // 를 그대로 쓴다(bodyless — .md 박스 안 두름). 지도에서 위치를 찍는 동안엔
    // hidden 으로 감추기만 한다(언마운트 아님) — 언마운트하면 폼 state 가 통째로
    // 날아가 지정하러 가기 전 입력이 사라진다. 그 동안엔 Esc 도 끈다.
    // 큰 폼이라 백드롭 클릭으론 닫지 않는다(closeOnBackdrop 기본 false).
    <Modal
      open
      onClose={handleCancelEdit}
      overlayClassName="blk-modal-ov"
      hidden={pinPickMode}
      bodyless
      closeOnEsc={!pinPickMode}
    >
      {(() => {
        const item = items[editingBlockId];
        const sMins = item.startMins;
        const eMins = sMins + item.dur;
        // Day 는 블록 자신의 오프셋에서 뽑는다 — 보고 있는 탭이 아니다.
        // 소속 규칙은 어디서나 시작 시각 기준(floor(offset/1440)+1)이다:
        // 서버 Block.dayNo(), 체인 소속, 챗봇 요약이 전부 그렇다.
        const dayNum = blockApi.dayNoOfOffset(sMins);
        // 자정을 넘는 블록은 "23:30 - 05:00" 이 하루 안에서 거꾸로 간 것처럼
        // 읽힌다 — 끝이 며칠 뒤인지 붙여 준다.
        const overDays =
          sMins == null ? 0 : blockApi.dayNoOfOffset(eMins) - dayNum;
        // 후보(POOL) 블록은 시각이 없다(느슨한 블록) — 폼이 "시간 정보 없음"을 띄운다
        const timeStr =
          sMins == null
            ? ""
            : `Day ${dayNum} · ${fmtTime(sMins)} - ${fmtTime(eMins)}${
                overDays > 0 ? ` (+${overDays}일)` : ""
              }`;

        const lockedByName = lockBadgeOf(editingBlockId);
        return (
          <BlockEditForm
            // 블록이 바뀌면 폼을 새로 만든다. formData 는 마운트 때 한 번만
            // initialData 로 씨를 뿌리므로, 같은 인스턴스를 재사용하면 A 의
            // 입력값을 든 채 저장 대상만 B 로 바뀌어 A 의 이름·비용·비고가
            // B 에 덮여 쓰인다. 지정 모드에선 모달을 감추기만 해서(언마운트
            // 아님) 그 동안 보드가 클릭 가능해졌고, 그래서 A→B 전환이 실제로
            // 닿을 수 있는 경로가 됐다. key 는 pinPickMode 로는 안 바뀌므로
            // "지정 중에도 폼을 살려 둔다"는 성질은 그대로다.
            key={editingBlockId}
            initialData={item}
            timeString={timeStr}
            // 서버가 category 필드 갱신을 지원하지 않는다(BLOCK400_2) —
            // 카테고리는 생성 시에만 정할 수 있다
            categoryLocked={!isTempId(editingBlockId)}
            // 상세락 — 남이 잡고 있으면 비고(detail) 입력을 잠근다.
            // 다른 필드는 필드 단위 LWW라 그대로 편집 가능(막지 않는다).
            lockNotice={
              lockedByName
                ? `✎ ${lockedByName} 님이 이 블록을 편집하고 있어요`
                : ""
            }
            detailLocked={Boolean(lockedByName)}
            // 락이 풀려 내가 이어받을 때 맞출 서버 최신 비고(라이브)
            serverDetail={items[editingBlockId]?.detail ?? ""}
            pinnedLocation={pinnedLocation}
            onRequestPinPick={handleRequestPinPick}
            onSave={handleSaveBlock}
            onCancel={handleCancelEdit}
            onReselectTransport={handleReselectTransport}
          />
        );
      })()}
    </Modal>
  );
}
