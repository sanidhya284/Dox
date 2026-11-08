# syntax=docker/dockerfile:1
# The "web" image (ADR-0011): Caddy serves the built UI and reverse-proxies /api, /ws and /actuator/health
# to the app, with automatic TLS. Build context: the repository root.
#   docker build -f deploy/web.Dockerfile -t chat-web:local .

FROM node:22.11.0-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2.8.4-alpine
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
