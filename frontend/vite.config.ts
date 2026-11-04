import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 루트 .env 하나로 서버·프론트 키를 함께 관리한다. 브라우저에는 VITE_ 접두사 변수만 노출된다
  envDir: '..',
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
