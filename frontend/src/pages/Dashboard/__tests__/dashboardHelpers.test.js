import { describe, it, expect } from "vitest";
import {
  fmtTime,
  fmtDur,
  won,
  isTempId,
  isServerBlock,
  catFromKakaoGroup,
  dayNoOf,
  dayKeysOf,
  effectiveCostOf,
  boardOf,
  realBlocksOfDay,
  omitKey,
} from "../dashboardHelpers";

describe("fmtTime", () => {
  it("자정=오전 12:00, 9시=오전 9:00, 14:30=오후 2:30", () => {
    expect(fmtTime(0)).toBe("오전 12:00");
    expect(fmtTime(540)).toBe("오전 9:00");
    expect(fmtTime(870)).toBe("오후 2:30");
  });
  it("하루를 넘긴 절대분은 그 Day 안의 시각으로 접는다", () => {
    expect(fmtTime(1470)).toBe("오전 12:30");
  });
  it("음수도 정규화한다", () => {
    expect(fmtTime(-30)).toBe("오후 11:30");
  });
});

describe("fmtDur", () => {
  it("null은 빈 문자열", () => expect(fmtDur(null)).toBe(""));
  it("60분 미만은 분", () => expect(fmtDur(45)).toBe("45분"));
  it("60분 이상은 시간+분", () => expect(fmtDur(75)).toBe("1시간 15분"));
  it("정각은 시간만", () => expect(fmtDur(120)).toBe("2시간"));
});

describe("won", () => {
  it("0/없음은 빈 문자열", () => expect(won(0)).toBe(""));
  it("만원 미만은 원", () => expect(won(500)).toBe("500원"));
  it("만원 이상은 만원 축약", () => expect(won(93000)).toBe("9.3만원"));
  it("정확히 만원 단위는 소수점 제거", () => expect(won(10000)).toBe("1만원"));
  it("100만원 이상은 반올림 정수", () => expect(won(1000000)).toBe("100만원"));
});

describe("id 규약", () => {
  it("custom-/search-는 임시 id", () => {
    expect(isTempId("custom-1")).toBe(true);
    expect(isTempId("search-2")).toBe(true);
    expect(isTempId("42")).toBe(false);
  });
  it("서버 블록은 임시·auto- 가 아닌 것", () => {
    expect(isServerBlock("42")).toBe(true);
    expect(isServerBlock("auto-1")).toBe(false);
    expect(isServerBlock("custom-1")).toBe(false);
  });
});

describe("catFromKakaoGroup", () => {
  it("FD6/CE7=food, AD5=stay, 그외=spot", () => {
    expect(catFromKakaoGroup("FD6")).toBe("food");
    expect(catFromKakaoGroup("CE7")).toBe("food");
    expect(catFromKakaoGroup("AD5")).toBe("stay");
    expect(catFromKakaoGroup("XX9")).toBe("spot");
  });
});

describe("dayNoOf / dayKeysOf", () => {
  it("d3 → 3", () => expect(dayNoOf("d3")).toBe(3));
  it("기간에서 Day 키 목록", () => {
    expect(dayKeysOf({ startDate: "2026-08-01", endDate: "2026-08-03" })).toEqual([
      "d1",
      "d2",
      "d3",
    ]);
  });
  it("기간 없으면 폴백 4일", () => {
    expect(dayKeysOf({})).toEqual(["d1", "d2", "d3", "d4"]);
  });
});

describe("effectiveCostOf", () => {
  it("1인당 요금 교통은 인원 곱", () => {
    const train = { cat: "trans", cost: 10000, transportMeta: { chosen: { mode: "TRAIN" } } };
    expect(effectiveCostOf(train, 3)).toBe(30000);
  });
  it("일반 블록은 인원과 무관", () => {
    expect(effectiveCostOf({ cat: "food", cost: 5000 }, 3)).toBe(5000);
  });
});

describe("boardOf", () => {
  it("startMins 없는 후보는 빼고 오프셋 오름차순 id", () => {
    const map = {
      a: { id: "a", startMins: 100 },
      b: { id: "b", startMins: 50 },
      c: { id: "c" },
    };
    expect(boardOf(map)).toEqual(["b", "a"]);
  });
});

describe("realBlocksOfDay", () => {
  it("그 Day 의 서버 블록(non-auto)만 남기고 auto/임시 id 는 뺀다", () => {
    const items = {
      "10": { id: "10", startMins: 60 }, // d1 서버 블록
      "11": { id: "11", startMins: 120 }, // d1 서버 블록
      "auto-1": { id: "auto-1", startMins: 90, auto: true }, // d1 자동 교통
      "custom-9": { id: "custom-9", startMins: 100 }, // d1 저장 중 임시
      "20": { id: "20", startMins: 1500 }, // d2 (제외)
    };
    const board = boardOf(items);
    expect(realBlocksOfDay(board, items, "d1")).toEqual(["10", "11"]);
    expect(realBlocksOfDay(board, items, "d2")).toEqual(["20"]);
  });
});

describe("omitKey", () => {
  it("키 하나만 뺀 새 객체를 돌려주고 원본은 안 건드린다", () => {
    const src = { a: 1, b: 2 };
    expect(omitKey(src, "a")).toEqual({ b: 2 });
    expect(src).toEqual({ a: 1, b: 2 });
  });
});
