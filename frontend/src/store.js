import { create } from "zustand";

const useStore = create((set, get) => ({
  token: null,
  user: null,
  videos: [],
  videosLoadedAt: null,
  videosOwner: null,

  setToken: (t) => set({ token: t }),
  setUser: (u) => set({ user: u }),
  clearAuth: () => set({ token: null, user: null, videos: [], videosLoadedAt: null, videosOwner: null }),

  setVideos: (v, owner = null) => set({ videos: v, videosLoadedAt: Date.now(), videosOwner: owner }),
  clearVideos: () => set({ videos: [], videosLoadedAt: null, videosOwner: null }),
  isVideoListStale: (owner = null) => {
    const state = get();
    return state.videosOwner !== owner || !state.videosLoadedAt || Date.now() - state.videosLoadedAt > 30000;
  },
}));

export default useStore;
