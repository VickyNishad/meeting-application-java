/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080';
  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        // API documentation (Swagger UI) through the same origin as the app
        '/swagger-ui': { target: proxyTarget, changeOrigin: true },
        '/v3/api-docs': { target: proxyTarget, changeOrigin: true },
        // OAuth2 login (Spring Security): keep the browser Host and send X-Forwarded-* so the backend builds
        // redirect URIs / post-login redirects against the dev-server origin.
        '/oauth2/': { target: proxyTarget, changeOrigin: false, xfwd: true },
        '/login/oauth2/': { target: proxyTarget, changeOrigin: false, xfwd: true },
      },
    },
    build: {
      sourcemap: true,
      chunkSizeWarningLimit: 900,
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
    },
  };
});
