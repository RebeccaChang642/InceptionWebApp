import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  // 用相對路徑，讓建置後的網站能放在 GitHub Pages 的子路徑 /InceptionWebApp/ 下正常載入資源
  base: './',
  server: {
    host: '127.0.0.1',
    port: 3000
  }
})
