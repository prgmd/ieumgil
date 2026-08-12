import { generateKeyBetween } from "fractional-indexing";
import { isServerBlock } from "./dashboardHelpers";
import * as blockApi from "../../features/dashboard/api/dashboardApi";

/**
 * 중복 orderKey 에 견디는 키 생성 (QA: 블록 이동 시 ">=" 오류 픽스).
 * 삭제 복구(원래 키 재사용)·동시 생성 등으로 이웃 블록의 키가 같아질 수 있는데,
 * fractional-indexing 은 before >= after 면 "a1 >= a1" 을 던지고 그게 토스트로
 * 새어 나왔다. 경계가 모순이면 한쪽 경계를 버리고 다시 만든다 — 그 상태에선
 * 상대 순서가 어차피 애매해서 서버의 (order_key, id) 동점 규칙이 순서를 정한다.
 */
export const safeKeyBetween = (before, after) => {
  try {
    return generateKeyBetween(before, after);
  } catch {
    try {
      return generateKeyBetween(before, null);
    } catch {
      try {
        return generateKeyBetween(null, after);
      } catch {
        return generateKeyBetween(null, null);
      }
    }
  }
};

/**
 * 최종 목록에서 pos 위치 블록의 양옆 orderKey 경계를 찾는다.
 * auto- 같은 로컬 전용 블록은 서버에 없어 orderKey 가 없으므로 건너뛰고
 * 가장 가까운 서버 블록의 키를 경계로 쓴다. 끝이면 null(개방 경계).
 */
export const neighborKeysAround = (finalList, pos, itemsMap) => {
  let before = null;
  for (let i = pos - 1; i >= 0; i -= 1) {
    const id = finalList[i];
    if (isServerBlock(id) && itemsMap[id]?.orderKey != null) {
      before = itemsMap[id].orderKey;
      break;
    }
  }
  let after = null;
  for (let i = pos + 1; i < finalList.length; i += 1) {
    const id = finalList[i];
    if (isServerBlock(id) && itemsMap[id]?.orderKey != null) {
      after = itemsMap[id].orderKey;
      break;
    }
  }
  return [before, after];
};

/**
 * orderKey 정렬 위치에 블록을 삽입한 새 배열 (원격 op 적용용).
 * 로컬 전용 블록(auto- 등, orderKey 없음)은 비교에서 건너뛴다 — 서버 블록들
 * 사이의 상대 위치만 orderKey 가 정하고, 로컬 블록은 제자리를 유지한다.
 * 동점은 id 로 판정한다(ERD: ORDER BY order_key, id).
 */
export const insertByOrderKey = (list, itemsMap, block) => {
  const without = list.filter((id) => id !== block.id);
  const at = without.findIndex((id) => {
    const other = itemsMap[id];
    if (!isServerBlock(id) || other?.orderKey == null) return false;
    if (other.orderKey > block.orderKey) return true;
    return (
      other.orderKey === block.orderKey && Number(other.id) > Number(block.id)
    );
  });
  without.splice(at === -1 ? without.length : at, 0, block.id);
  return without;
};

/**
 * 겹침 해소(resolveOverlaps)로 밀린 체인 내 서버 블록들의 시작 오프셋을
 * position PATCH 로 저장한다 — 이웃도 시간축 위치가 바뀐 것이다.
 * 편집(3단계)·이동(5단계)·리사이즈가 공유한다 — 로컬만 밀면 새로고침 때
 * 이웃들이 옛 자리로 되돌아간다(명세 320행: 재계산 저장은 클라이언트 몫).
 * 서버 블록만 보낸다 — 임시 id 는 아직 서버 행이 없고 position 엔드포인트는
 * 비어 있지 않은 orderKey 를 요구한다.
 */
export const persistMovedOffsets = (chainIds, prevItems, nextItems, excludeId) => {
  const shifted = (chainIds ?? []).filter(
    (id) =>
      id !== excludeId &&
      isServerBlock(id) &&
      nextItems[id]?.startMins != null &&
      nextItems[id].startMins !== prevItems[id]?.startMins,
  );
  // 순서(orderKey)는 그대로 다시 보낸다 — 바뀐 건 시간축 위치뿐이다
  return Promise.all(
    shifted.map((id) =>
      blockApi.moveBlock(id, {
        startOffsetMinutes: nextItems[id].startMins,
        orderKey: nextItems[id].orderKey,
      }),
    ),
  );
};

/**
 * 보드 위 블록들의 겹침을 해소한다. 시각은 모두 절대 오프셋(Day 1 00:00 기준 분)이라
 * 겹치지 않는 블록은 제자리에 두고(공백 보존), 겹치는 블록만 앞 블록 끝까지 뒤로 민다.
 * fixedId 가 있으면 그 블록만 고정하고 나머지가 비켜난다.
 *
 * boardChain 은 보드 전체다 — Day 별로 나눠 돌리지 않는다. 축이 여행 한 줄이고
 * 오프셋 하나가 Day 와 그 안의 시각을 함께 가리키므로, Day 경계는 해소가 알아야
 * 할 것이 아니다. 자정을 넘겨 이어지는 블록도 그냥 목록 안의 앞 블록이다.
 *
 * 밀림은 Math.max(lastEnd, startMins) 하나로 끝난다 — 겹치지 않는 블록을 만나면
 * 그 블록은 제자리에 남고 lastEnd 가 그 자리로 갱신되므로, 전파가 첫 공백에서
 * 저절로 멈춘다. 밤마다 여덟 시간짜리 공백이 있으니 Day 를 넘는 밀림은 그 Day 가
 * 자정까지 꽉 찼을 때뿐이다.
 */
export const resolveOverlaps = (currentItems, boardChain, fixedId) => {
  let newItems = { ...currentItems };
  const others = boardChain.filter((id) => id !== fixedId);
  others.sort((a, b) => newItems[a].startMins - newItems[b].startMins);

  const fixedStart = fixedId ? newItems[fixedId].startMins : -1;
  const fixedEnd = fixedId ? fixedStart + newItems[fixedId].dur : -1;

  // 보드의 바닥은 여행 첫 Day 의 00:00 = 0 이다. Day 별 바닥(자정을 넘어온 블록의
  // 끝)이라는 개념은 없어졌다 — 그 블록이 이미 이 목록 안의 앞 블록이라 lastEnd 가
  // 같은 일을 Day 경계 없이 한다.
  let lastEnd = 0;

  others.forEach((id) => {
    let start = Math.max(lastEnd, newItems[id].startMins);
    if (fixedId) {
      let end = start + newItems[id].dur;
      if (start < fixedEnd && end > fixedStart)
        start = Math.max(start, fixedEnd);
    }
    newItems[id] = { ...newItems[id], startMins: start };
    lastEnd = start + newItems[id].dur;
  });

  const newChain = [...boardChain].sort(
    (a, b) => newItems[a].startMins - newItems[b].startMins,
  );
  return { newItems, newChain };
};
