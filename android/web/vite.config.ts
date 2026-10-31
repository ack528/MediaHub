import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// 构建产物直接放进 APK 的 assets/web,由 WebView 通过 https://appassets.androidplatform.net/ 读取(见 WebMedia.kt)
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss()],
  build: {
    outDir: "../app/src/main/assets/web",
    emptyOutDir: true,
    target: "chrome111",
    chunkSizeWarningLimit: 2000,
  },
});
