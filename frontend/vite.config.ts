import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the browser talks only to the Vite server (localhost:5173), which forwards
// /api/* to the Spring Boot backend. Same origin from the browser's view, so no CORS needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.VITE_BACKEND_URL ?? 'http://localhost:8080',
    },
  },
});
