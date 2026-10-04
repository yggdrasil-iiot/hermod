import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The authoring SPA talks to the :authoring-api Spring Boot edge module (dev port 18090).
// All UI calls are relative ("/api/..."), proxied here in dev so there is no CORS dance.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:18090",
        changeOrigin: true,
      },
    },
  },
});
