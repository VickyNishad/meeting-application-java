# syntax=docker/dockerfile:1

# ---- Build stage ---------------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_PUBLIC_APP_URL=
ARG VITE_SPEAKING_RELEASE_MS=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_PUBLIC_APP_URL=$VITE_PUBLIC_APP_URL \
    VITE_SPEAKING_RELEASE_MS=$VITE_SPEAKING_RELEASE_MS
RUN npm run build

# ---- Runtime stage -------------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1/healthz || exit 1
CMD ["nginx", "-g", "daemon off;"]
