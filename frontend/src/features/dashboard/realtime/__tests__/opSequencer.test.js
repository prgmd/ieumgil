import { describe, it, expect } from "vitest";
import { createOpSequencer } from "../opSequencer";

const collector = () => {
  const applied = [];
  return {
    applied,
    seq: createOpSequencer({
      apply: (op) => applied.push(op.seq),
      fetchOpsAfter: async () => [],
    }),
  };
};

describe("createOpSequencer", () => {
  it("스냅샷(reset) 전에는 버퍼만, reset 후 순서대로 적용", () => {
    const { applied, seq } = collector();
    seq.push({ seq: 1, type: "x" });
    seq.push({ seq: 2, type: "x" });
    expect(applied).toEqual([]); // cursor 미정
    seq.reset(0);
    expect(applied).toEqual([1, 2]);
  });

  it("연속 seq를 커서 전진하며 적용", () => {
    const { applied, seq } = collector();
    seq.reset(0);
    seq.push({ seq: 1, type: "x" });
    seq.push({ seq: 2, type: "x" });
    expect(applied).toEqual([1, 2]);
  });

  it("커서 이하 seq는 중복으로 드롭", () => {
    const { applied, seq } = collector();
    seq.reset(5);
    seq.push({ seq: 3, type: "x" }); // <= cursor → drop
    seq.push({ seq: 6, type: "x" });
    expect(applied).toEqual([6]);
  });

  it("갭이 나면 fetchOpsAfter로 메꿔 이어 적용", async () => {
    const applied = [];
    const seq = createOpSequencer({
      apply: (op) => applied.push(op.seq),
      fetchOpsAfter: async (after) => (after === 0 ? [{ seq: 1, type: "x" }, { seq: 2, type: "x" }] : []),
    });
    seq.reset(0);
    seq.push({ seq: 3, type: "x" }); // 갭(1,2 없음) → fetch
    await new Promise((r) => setTimeout(r, 0));
    expect(applied).toEqual([1, 2, 3]);
    seq.dispose();
  });
});
