import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "astro/config"

export default defineConfig({
  site: "https://kubedeck.pages.dev",
  output: "static",
  vite: { plugins: [tailwindcss()] },
})
