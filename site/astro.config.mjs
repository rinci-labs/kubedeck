import tailwindcss from "@tailwindcss/vite"
import react from "@astrojs/react"
import { defineConfig } from "astro/config"

export default defineConfig({
  site: "https://kubedeck.rin.ci",
  output: "static",
  integrations: [react()],
  vite: { plugins: [tailwindcss()] },
})
