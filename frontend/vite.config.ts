import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
  },
  server: {
    proxy: {
      '/decker/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        rewriteWsOrigin: true,
      },
    },
  },
})
