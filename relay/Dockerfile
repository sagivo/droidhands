# syntax=docker/dockerfile:1

# ---- build: compile TypeScript ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# ---- runtime: production dependencies + compiled JS only ----
FROM node:22-alpine
ENV NODE_ENV=production \
    PORT=8787 \
    HOST=0.0.0.0 \
    DATA_DIR=/data
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /app/dist ./dist
# Optional: put phonebridge.apk into relay/public/ before building and /setup offers it for download.
COPY public ./public

# The relay's only state is DATA_DIR/relay-secret (the key that signs OAuth client ids; or set
# RELAY_SECRET instead). /data belongs to the unprivileged `node` user, and a named volume created
# from this image inherits that ownership. The application directory stays root-owned, read-only.
RUN mkdir -p /data && chown node:node /data && chmod 700 /data
VOLUME ["/data"]

USER node
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/healthz" || exit 1
STOPSIGNAL SIGTERM
CMD ["node", "dist/server.js"]
