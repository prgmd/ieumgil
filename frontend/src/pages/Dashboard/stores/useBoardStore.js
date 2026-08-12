import { create } from "zustand";

const INITIAL = { items: {}, pool: [], editingBlockId: null };

export const useBoardStore = create((set) => ({
  ...INITIAL,
  setItems: (u) => set((s) => ({ items: typeof u === "function" ? u(s.items) : u })),
  setPool: (u) => set((s) => ({ pool: typeof u === "function" ? u(s.pool) : u })),
  setEditingBlockId: (u) =>
    set((s) => ({ editingBlockId: typeof u === "function" ? u(s.editingBlockId) : u })),
  resetBoard: () => set(INITIAL),
}));
