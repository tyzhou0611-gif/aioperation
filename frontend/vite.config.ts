import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

const apiProxy = {
  '/api': {
    target: 'http://localhost:8181',
    changeOrigin: true
  },
  '/h2-console': {
    target: 'http://localhost:8181',
    changeOrigin: true
  },
  '/ws': {
    target: 'http://localhost:8181',
    changeOrigin: true,
    ws: true
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: apiProxy
  },
  // vue-tsc 不再向源码目录输出 vite.config.js；
  // preview 用于本地验证「前端静态包 + 后端 API」分离部署
  preview: {
    port: 4173,
    proxy: apiProxy
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    // 生产环境删除 console
    minify: 'esbuild'
  }
})