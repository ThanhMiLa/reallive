# syntax=docker/dockerfile:1

FROM node:24-bookworm-slim AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json nest-cli.json ./
COPY src ./src
RUN npm run build
RUN npm prune --omit=dev


FROM node:24-bookworm-slim AS runtime

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 mediaworker \
    && useradd --uid 10001 --gid mediaworker --home-dir /app --no-create-home --shell /usr/sbin/nologin mediaworker \
    && mkdir -p /app /var/lib/media-worker/tmp \
    && chown -R mediaworker:mediaworker /app /var/lib/media-worker

WORKDIR /app

ENV NODE_ENV=production \
    PORT=8090 \
    MEDIA_WORKER_TEMP_DIR=/var/lib/media-worker/tmp

COPY --from=build --chown=mediaworker:mediaworker /app/node_modules ./node_modules
COPY --from=build --chown=mediaworker:mediaworker /app/dist ./dist
COPY --chown=mediaworker:mediaworker package.json ./

USER mediaworker

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD ["node", "-e", "const port = process.env.PORT || 8090; fetch(`http://127.0.0.1:${port}/health/live`).then((response) => { if (!response.ok) process.exit(1); }).catch(() => process.exit(1));"]

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "dist/main.js"]
