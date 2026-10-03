# KubeDeck site

Static landing page built with Astro 5 and Tailwind CSS 4. The GitHub Pages project base is configured in `astro.config.mjs`.

## Local development

Install Bun, then run:

```sh
bun install --frozen-lockfile
bun run dev
```

Build the static site with `bun run build`; output is written to `dist/`.

The APK download links target the public `v0.1.0` GitHub Release asset. Check that release before changing the release version or asset path.
