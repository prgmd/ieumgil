import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // VITE_ 접두사 없는 값도 읽기 위해 세 번째 인자를 ''로 둔다
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: {
      // /api 요청을 dev server가 백엔드로 중계한다.
      // 브라우저 입장에서는 전부 localhost:5173 한 오리진이므로
      // refreshToken 쿠키가 first-party로 저장되고(SameSite=Lax 유지) CORS도 발생하지 않는다.
      // 백엔드를 원격 개발서버에 띄웠을 때 프론트를 로컬에서 돌리려면 이 프록시가 필수다.
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
