/**
 * op 순서 보장 계층 — 수신(useProjectOps)과 적용(화면) 사이에 선다.
 *
 * 서버 규약(OpPublisher): seq 채번 락이 커밋까지 잡지 않으므로 **seq 5가 6보다
 * 늦게 브로드캐스트될 수 있다**. 받는 대로 적용하고 커서를 점프시키면 늦게 온
 * 5를 "중복"으로 오인해 영영 잃는다. 그래서:
 *
 *   op.seq == cursor+1 → 적용, 커서 전진, 버퍼에서 이어지는 것 연쇄 적용
 *   op.seq >  cursor+1 → 버퍼 보관 + ops?afterSeq={cursor} 로 갭 fetch
 *                        (fetch 에도 없으면 = 아직 미커밋 역전 → 잠깐 후 재시도)
 *   op.seq <= cursor   → 드롭 — 브로드캐스트와 갭 fetch 가 겹치면 정상적으로 발생
 *
 * 커서의 시작점은 스냅샷의 lastSeq 다(reset). 스냅샷보다 WS 가 먼저 op 를 받는
 * 초기 레이스는 "cursor 미정 동안 버퍼만" 규칙이 흡수한다 — reset 때 lastSeq
 * 이하를 버리고 나머지를 순서대로 적용한다.
 *
 * 자기 op 스킵은 여기서 하지 않는다 — 자기 op 도 seq 를 소비하므로 커서는
 * 전진해야 한다. payload 를 적용할지는 apply 콜백(수신 측)이 clientId 로 판단한다.
 *
 * React 밖의 순수 모듈이다 — 시퀀싱 로직은 상태·타이머가 얽혀 있어서,
 * 렌더 주기와 분리해 두는 쪽이 검증하기 쉽다.
 */
export function createOpSequencer({ fetchOpsAfter, apply, retryDelayMs = 500 }) {
  let cursor = null; // 마지막으로 소비한 seq. null = 스냅샷 도착 전(버퍼만 쌓는다)
  const buffer = new Map(); // seq → op
  let fetching = false;
  let retryTimer = null;
  let disposed = false;

  function push(op) {
    if (disposed || op?.seq == null) return;
    if (cursor !== null && op.seq <= cursor) return; // 중복
    buffer.set(op.seq, op);
    drain();
  }

  /** 스냅샷 (재)시드 시점에 호출 — 커서를 스냅샷 기준으로 맞추고 밀린 것을 정리한다 */
  function reset(lastSeq) {
    if (disposed) return;
    cursor = lastSeq;
    for (const seq of [...buffer.keys()]) {
      if (seq <= lastSeq) buffer.delete(seq);
    }
    clearRetry();
    drain();
  }

  /** 재연결 직후 등 "지금 따라잡아라" 트리거 (4단계에서 사용) */
  function syncNow() {
    if (disposed || cursor === null) return;
    fillGap();
  }

  function dispose() {
    disposed = true;
    clearRetry();
    buffer.clear();
  }

  // ── 내부 ──────────────────────────────────────────
  function drain() {
    if (cursor === null) return; // 스냅샷 전 — 적용 기준이 없다

    while (buffer.has(cursor + 1)) {
      const op = buffer.get(cursor + 1);
      buffer.delete(cursor + 1);
      cursor += 1;
      try {
        apply(op);
      } catch (e) {
        // 한 op 의 적용 실패가 스트림 전체를 멈추면 안 된다 — 기록하고 계속 간다
        console.error("[realtime] op 적용 실패:", op?.type, e);
      }
    }

    if (buffer.size > 0) fillGap();
    else clearRetry();
  }

  async function fillGap() {
    if (fetching || retryTimer || disposed || cursor === null) return;

    fetching = true;
    try {
      const ops = await fetchOpsAfter(cursor);
      if (disposed) return;
      for (const op of ops ?? []) {
        if (op?.seq != null && op.seq > cursor) buffer.set(op.seq, op);
      }
    } catch (e) {
      console.warn("[realtime] 갭 fetch 실패 — 다음 수신/재시도가 다시 시도한다:", e);
    } finally {
      fetching = false;
    }

    drain();

    // drain 후에도 갭이 남았다 = 앞선 seq 가 아직 미커밋(순서 역전) — 잠깐 뒤 재시도
    if (!disposed && buffer.size > 0 && !retryTimer) {
      retryTimer = setTimeout(() => {
        retryTimer = null;
        fillGap();
      }, retryDelayMs);
    }
  }

  function clearRetry() {
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
  }

  return { push, reset, syncNow, dispose };
}
