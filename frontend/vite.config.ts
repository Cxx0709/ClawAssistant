import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// 构建产物直接输出到 Spring Boot 静态根目录（评委 `mvn spring-boot:run` 即用）
const staticDir = fileURLToPath(
  new URL('../src/main/resources/static', import.meta.url),
);

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: staticDir,
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期把 API 代理到本地 Spring Boot（8080）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
