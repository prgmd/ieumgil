import { describe, it, expect } from "vitest";
import {
  safeKeyBetween,
  neighborKeysAround,
  insertByOrderKey,
  resolveOverlaps,
} from "../boardOrdering";

describe("safeKeyBetween", () => {
  it("빈 경계는 a0", () => expect(safeKeyBetween(null, null)).toBe("a0"));
  it("정상 경계 사이 키를 만든다", () => {
    const k = safeKeyBetween("a0", "a1");
    expect(typeof k).toBe("string");
    expect(k > "a0" && k < "a1").toBe(true);
  });
  it("모순 경계(before>after)도 던지지 않고 문자열 반환", () => {
    const k = safeKeyBetween("a1", "a0");
    expect(typeof k).toBe("string");
  });
});

describe("neighborKeysAround", () => {
  const items = {
    "1": { id: "1", orderKey: "a0" },
    "2": { id: "2", orderKey: "a1" },
    "3": { id: "3", orderKey: "a2" },
  };
  it("양옆 서버 블록의 orderKey를 경계로", () => {
    expect(neighborKeysAround(["1", "2", "3"], 1, items)).toEqual(["a0", "a2"]);
  });
  it("로컬(auto-) 블록은 건너뛰고 가까운 서버 키를 쓴다", () => {
    const m = { "1": { id: "1", orderKey: "a0" }, "3": { id: "3", orderKey: "a2" } };
    expect(neighborKeysAround(["1", "auto-x", "3"], 1, m)).toEqual(["a0", "a2"]);
  });
  it("끝이면 개방 경계(null)", () => {
    expect(neighborKeysAround(["1", "2", "3"], 0, items)).toEqual([null, "a1"]);
  });
});

describe("insertByOrderKey", () => {
  it("orderKey 정렬 위치에 삽입", () => {
    const list = ["1", "3"];
    const items = { "1": { id: "1", orderKey: "a0" }, "3": { id: "3", orderKey: "a2" } };
    const block = { id: "2", orderKey: "a1" };
    expect(insertByOrderKey(list, items, block)).toEqual(["1", "2", "3"]);
  });
  it("가장 큰 키는 맨 뒤", () => {
    const list = ["1"];
    const items = { "1": { id: "1", orderKey: "a0" } };
    expect(insertByOrderKey(list, items, { id: "9", orderKey: "a5" })).toEqual(["1", "9"]);
  });
});

describe("resolveOverlaps", () => {
  it("겹치는 블록만 앞 블록 끝까지 뒤로 민다", () => {
    const cur = { a: { startMins: 0, dur: 60 }, b: { startMins: 30, dur: 60 } };
    const { newItems, newChain } = resolveOverlaps(cur, ["a", "b"], null);
    expect(newItems.b.startMins).toBe(60);
    expect(newItems.a.startMins).toBe(0);
    expect(newChain).toEqual(["a", "b"]);
  });
  it("겹치지 않으면 제자리(공백 보존)", () => {
    const cur = { a: { startMins: 0, dur: 60 }, c: { startMins: 200, dur: 30 } };
    const { newItems } = resolveOverlaps(cur, ["a", "c"], null);
    expect(newItems.c.startMins).toBe(200);
  });
});
