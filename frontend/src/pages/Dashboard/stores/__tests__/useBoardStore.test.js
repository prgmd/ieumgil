import { describe, it, expect, beforeEach } from "vitest";
import { useBoardStore } from "../useBoardStore";

const get = () => useBoardStore.getState();

describe("useBoardStore", () => {
  beforeEach(() => get().resetBoard());

  it("초기값", () => {
    expect(get().items).toEqual({});
    expect(get().pool).toEqual([]);
    expect(get().editingBlockId).toBe(null);
  });

  it("setItems는 값 형태를 받는다", () => {
    get().setItems({ a: { id: "a" } });
    expect(get().items).toEqual({ a: { id: "a" } });
  });

  it("setItems는 updater 함수 형태를 받는다(useState 호환)", () => {
    get().setItems({ a: { id: "a" } });
    get().setItems((prev) => ({ ...prev, b: { id: "b" } }));
    expect(Object.keys(get().items)).toEqual(["a", "b"]);
  });

  it("setPool도 값/updater 양형", () => {
    get().setPool(["x"]);
    expect(get().pool).toEqual(["x"]);
    get().setPool((prev) => [...prev, "y"]);
    expect(get().pool).toEqual(["x", "y"]);
  });

  it("setEditingBlockId 값/updater 양형", () => {
    get().setEditingBlockId("b1");
    expect(get().editingBlockId).toBe("b1");
    get().setEditingBlockId((prev) => (prev === "b1" ? null : prev));
    expect(get().editingBlockId).toBe(null);
  });

  it("getState는 set 직후 동기 반영(op 배치용)", () => {
    get().setItems({ a: { id: "a", startMins: 0 } });
    expect(useBoardStore.getState().items.a.startMins).toBe(0);
    get().setItems((prev) => ({ ...prev, a: { ...prev.a, startMins: 60 } }));
    expect(useBoardStore.getState().items.a.startMins).toBe(60);
  });

  it("resetBoard는 초기값으로 되돌린다", () => {
    get().setItems({ a: 1 });
    get().setPool(["x"]);
    get().setEditingBlockId("b");
    get().resetBoard();
    expect(get().items).toEqual({});
    expect(get().pool).toEqual([]);
    expect(get().editingBlockId).toBe(null);
  });
});
