# KubeDeck site

Static landing page built with Astro 5 and Tailwind CSS 4. The production site is hosted on Cloudflare Pages at `https://kubedeck.pages.dev/`; `astro.config.mjs` sets the canonical site URL.

## Local development

Install Bun, then run:

```sh
bun install --frozen-lockfile
bun run dev
```

Build the static site with `bun run build`; output is written to `dist/`.

## Cloudflare Pages

Set the Pages project root directory to `site`, build command to `bun run build`, and build output directory to `dist`. The committed lockfile is Bun lockfile version 1, which works with Cloudflare Pages' default Bun 1.2.15 and is also frozen-install compatible with Bun 1.4.2. No Bun version dashboard setting is required. If you prefer to keep lockfile version 2, Cloudflare documents `BUN_VERSION=1.4.2` as the build-image override; it must be set in **Workers & Pages** > your Pages project > **Settings** > **Environment variables** for Production and Preview, since `.bun-version` is not a supported Bun selector.

The canonical site URL is `https://kubedeck.pages.dev/`.

The APK download links target the public `v0.1.0` GitHub Release asset. Check that release before changing the release version or asset path.
