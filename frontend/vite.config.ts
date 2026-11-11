import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 前缀必须和「后端真实存在的接口路径」一一对齐（2026-11-10 踩过：加了 /auth 与 /admin
      // 却没加规则，浏览器直接 404 或拿到 index.html，页面表现为"登录失败"）
      '/assistant': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
      '/admin': 'http://localhost:8080',
      '/tickets': 'http://localhost:8080',
      '/orders': 'http://localhost:8080',
      '/knowledge': 'http://localhost:8080',
      '/products': 'http://localhost:8080'
    }
  },
  test: {
    environment: 'jsdom',
    globals: true
  }
})
