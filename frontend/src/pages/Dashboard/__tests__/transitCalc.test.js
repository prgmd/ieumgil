import { describe, it, expect } from "vitest";
import { doorToDoorDurOf, transitDurOf, transitCostOf } from "../transitMeta";

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
