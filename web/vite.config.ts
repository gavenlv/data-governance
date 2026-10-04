import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 控制面地址：Java 控制面默认 8081（docs/10 §1 选型）
const CONTROL_PLANE = process.env.DG_API_URL ?? 'http://127.0.0.1:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: CONTROL_PLANE, changeOrigin: true },
      '/healthz': { target: CONTROL_PLANE, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    // 构建产物由 dg-api 作为静态资源托管（生产形态）
    sourcemap: true,
  },
})
