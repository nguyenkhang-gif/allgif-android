import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: '.',
  server: {
    port: 3010,
    host: true,
  },
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main:  resolve(__dirname, 'index.html'),
        popup: resolve(__dirname, 'popup.html'),
      },
    },
  },
});
