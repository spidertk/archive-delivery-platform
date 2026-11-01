import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', proxy: { '/api': 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom' }
})
