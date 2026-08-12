import { describe, it, expect } from "vitest";
import {
  doorToDoorDurOf,
  transitDurOf,
  transitCostOf,
  initialCandidateOf,
} from "../transitMeta";

describe("initialCandidateOf", () => {
  const A = { mode: "TRANSIT", status: "OK" };
  const B = { mode: "TRAIN", status: "OK" };
  const NG = { mode: "AIR", status: "NG" };

  it("defaultMode 가 null 이면 null (자동 선택 안 함)", () => {
    expect(initialCandidateOf({ defaultMode: null, candidates: [A, B] })).toBe(null);
  });
  it("segment 자체가 없으면 null", () => {
    expect(initialCandidateOf(undefined)).toBe(null);
  });
  it("defaultMode 와 mode 가 맞고 OK 인 후보를 우선 고른다", () => {
    expect(
      initialCandidateOf({ defaultMode: "TRAIN", candidates: [A, B] }),
    ).toBe(B);
  });
  it("defaultMode 매치가 없으면 첫 OK 후보로 폴백", () => {
    expect(
      initialCandidateOf({ defaultMode: "TAXI", candidates: [NG, A, B] }),
    ).toBe(A);
  });
  it("OK 후보가 하나도 없으면 null", () => {
    expect(
      initialCandidateOf({ defaultMode: "AIR", candidates: [NG] }),
    ).toBe(null);
  });
  it("candidates 가 없으면 null", () => {
    expect(initialCandidateOf({ defaultMode: "TRAIN" })).toBe(null);
  });
});

describe("doorToDoorDurOf", () => {
  it("departure 없으면 null", () => {
    expect(doorToDoorDurOf({ accessMin: 5 }, null)).toBe(null);
  });
  it("환승 없는 편은 접근+대기+시외+이탈 합", () => {
    expect(
      doorToDoorDurOf({ accessMin: 5, egressMin: 5 }, { waitMin: 10, durationMin: 60 }),
    ).toBe(80);
  });
  it("조각이 하나라도 null이면 null", () => {
    expect(doorToDoorDurOf({ egressMin: 5 }, { waitMin: 10, durationMin: 60 })).toBe(null);
  });
  it("연결편(환승)은 transferMin+durationMin도 더한다", () => {
    expect(
      doorToDoorDurOf(
        { accessMin: 5, egressMin: 5 },
        { waitMin: 10, durationMin: 60, connection: { transferMin: 5, durationMin: 30 } },
      ),
    ).toBe(115);
  });
});

describe("transitDurOf", () => {
  it("door-to-door 계산 가능하면 그 값(최소 10)", () => {
    expect(transitDurOf({ accessMin: 5, egressMin: 5 }, { waitMin: 10, durationMin: 60 })).toBe(80);
  });
  it("계산 불가면 편/후보 값으로 폴백", () => {
    expect(transitDurOf({}, { durationMin: 40 })).toBe(40);
  });
  it("아무 것도 없으면 10", () => {
    expect(transitDurOf({}, {})).toBe(10);
  });
});

describe("transitCostOf", () => {
  it("편 fare 우선", () => expect(transitCostOf({}, { fare: 2500 })).toBe(2500));
  it("fareOptions.general 차선", () => expect(transitCostOf({}, { fareOptions: { general: 3000 } })).toBe(3000));
  it("후보 fare 그 다음", () => expect(transitCostOf({ fare: 1000 }, {})).toBe(1000));
  it("없으면 0", () => expect(transitCostOf({}, {})).toBe(0));
});
