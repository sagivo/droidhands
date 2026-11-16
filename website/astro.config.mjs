// @ts-check
import { defineConfig } from "astro/config";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  site: "https://droidhands.com",
  vite: { plugins: [tailwindcss()] },
});
