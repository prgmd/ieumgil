import { describe, it, expect } from "vitest";
import { buildTransportMeta, transitRouteSummary } from "../transitMeta";

describe("buildTransportMeta", () => {
  it("전부 null이면 빈 메타", () => {
    expect(buildTransportMeta(null, null, null)).toEqual({
      generated: true,
      segment: null,
      chosen: null,
      candidates: [],
    });
  });
  it("chosen에 departureName을 얹고 segment 메타만 추린다", () => {
    const seg = {
      intercity: true,
      timetableApplied: false,
      timetableSkipReason: null,
      candidates: [{ id: 1 }],
      extra: "무시됨",
    };
    const meta = buildTransportMeta(seg, { mode: "TRAIN", id: 7 }, { name: "KTX101" });
    expect(meta.segment).toEqual({
      intercity: true,
      timetableApplied: false,
      timetableSkipReason: null,
    });
    expect(meta.chosen).toEqual({ mode: "TRAIN", id: 7, departureName: "KTX101" });
    expect(meta.candidates).toEqual([{ id: 1 }]);
  });
});

describe("transitRouteSummary", () => {
  it("경로 정보 없으면 null", () => {
    expect(transitRouteSummary({})).toBe(null);
  });
  it("시내 legs를 한 줄로 잇고 transferCount를 넘긴다", () => {
    const item = {
      transportMeta: {
        chosen: {
          legs: [
            { type: "WALK", durationMin: 5 },
            { lineName: "272", from: "A", to: "B" },
          ],
          transferCount: 1,
        },
      },
    };
    expect(transitRouteSummary(item)).toEqual({
      text: "도보 5분 · 272 A→B",
      transfers: 1,
    });
  });
  it("시외는 고른 편 하나를 요약한다", () => {
    const item = {
      transportMeta: {
        chosen: {
          departureName: "KTX101",
          departures: [{ name: "KTX101", departureAt: "09:00", arrivalAt: "11:30" }],
        },
      },
    };
    expect(transitRouteSummary(item)).toEqual({
      text: "KTX101 09:00→11:30",
      transfers: null,
    });
  });
});
