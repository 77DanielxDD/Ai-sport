import { create } from "zustand";

const useStore = create((set, get) => ({
  token: null,
  user: null,
  videos: [],
  videosLoadedAt: null,

  setToken: (t) => set({ token: t }),
  setUser: (u) => set({ user: u }),
  clearAuth: () => set({ token: null, user: null }),

  setVideos: (v) => set({ videos: v, videosLoadedAt: Date.now() }),
  isVideoListStale: () => !get().videosLoadedAt || Date.now() - get().videosLoadedAt > 30000,
}));

export default useStore;
