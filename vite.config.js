import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Safari 15+ (iOS 15+): newer esbuild versions can't lower destructuring for Safari 14.
    target: ['es2020', 'edge88', 'firefox78', 'chrome87', 'safari15'],
  },
});
