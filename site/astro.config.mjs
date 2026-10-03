import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "astro/config"

export default defineConfig({
  site: "https://rinci-labs.github.io",
  base: "/kubedeck",
  output: "static",
  vite: { plugins: [tailwindcss()] },
})
