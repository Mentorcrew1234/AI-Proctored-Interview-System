import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The exam app is served by Spring Boot under /exam/, so the build output goes
// straight into the backend's static resources and the final artefact stays a
// single runnable JAR.
export default defineConfig({
  plugins: [react()],
  base: '/exam/',
  build: {
    outDir: '../src/main/resources/static/exam',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // During `npm run dev` the API still lives on the Spring Boot port.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    // Node by default: most tests here are pure modules (event engine, uploader,
    // countdown maths) and need no DOM. A component test opts into jsdom with a
    // `@vitest-environment jsdom` docblock rather than slowing every file down.
    environment: 'node',
    include: ['src/**/*.test.{js,jsx}'],
  },
});
