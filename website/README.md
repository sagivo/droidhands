# DroidHands website

A single-page Astro + Tailwind site. No JavaScript is shipped and nothing loads from third parties.

```bash
npm install
npm run dev      # http://localhost:4321
npm run build    # static output in dist/
```

Links (GitHub repo, download, docs) live in `src/site.ts`.

## Deploying

Cloudflare Pages (project `droidhands`) is connected to this repository and builds the site
itself: root directory `website`, `npm run build`, output `dist`, `NODE_VERSION=24`. A push to
`main` that changes something under `website/` deploys to production; a push to any other branch
that does gets a preview URL (posted on its pull request). Pushes that touch nothing under
`website/` don't build.
