import { create } from 'zustand';

let hideTimer = null;

export const useToastStore = create((set) => ({
  message: '',
  visible: false,

  show: (message, duration = 2200) => {
    clearTimeout(hideTimer);
    set({ message, visible: true });
    hideTimer = setTimeout(() => set({ visible: false }), duration);
  },
}));
