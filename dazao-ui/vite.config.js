import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发请求通过同源代理携带会话 Cookie，独立于旧社区的开发端口。
export default defineConfig({
  base: process.env.VITE_BASE_PATH || '/',
  plugins: [vue()],
  server: {
    port: 5199,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8099', changeOrigin: true } }
  }
})
