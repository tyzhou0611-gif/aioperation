import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src')
        }
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8181',
                changeOrigin: true
            },
            '/h2-console': {
                target: 'http://localhost:8181',
                changeOrigin: true
            }
        }
    },
    build: {
        outDir: 'dist',
        assetsDir: 'assets',
        // 生产环境删除 console
        minify: 'esbuild'
    }
});
