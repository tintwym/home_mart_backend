# Home Mart Backend (Spring Boot 4.1 / Java 21)

Drop-in replacement for the previous ASP.NET Core API. Deploy to **Render** (or Railway/Fly)
and point the Vercel React SPA at it.

## What it owns

- Inertia-compatible page JSON for every React route (`/`, `/login`, `/listings/…`, …)
- JSON API under `/api` (+ `/mapi` alias for mobile)
- Schema via Hibernate (`ddl-auto=update`) + optional category/listing seeders
- Auth (cookie JWT `hm_token` for the SPA, Bearer for `/api`), password reset, email verify
- Firebase Google / Apple sign-in (`POST /auth/firebase`, `POST /api/auth/firebase`) → same `hm_token`
- Stripe checkout + promote + card vault
- Cloudinary uploads (local disk fallback)
- TOTP 2FA + WebAuthn passkeys (challenges stored in DB)
- Unread chat notifications at `/notifications`
- Region detection (cookie → GPS → IP → timezone)

## Requirements

- Java 21+
- Maven 3.9+
- PostgreSQL 14+ (Neon or local)

## Local development

```bash
cd home_mart_backend
cp .env.example .env
# Set DATABASE_URL (or SPRING_DATASOURCE_*) and JWT_KEY at minimum
# Local profile: SPRING_PROFILES_ACTIVE=local (application-local.properties; DataSeeder seeds demo data)

set -a && source .env && set +a
mvn spring-boot:run
# http://localhost:5199
```

Then start the frontend (`npm run dev` in `home_mart_frontend`) — Vite proxies to port **5199**.

## Local Docker (same image Render builds)

```bash
# Postgres must already be reachable (e.g. docker run homemart-pg on :5432)
cd home_mart_backend
docker compose up --build
# http://localhost:5199/api/health
```

Uses `Dockerfile` + `DATABASE_URL` via `host.docker.internal`. For Neon, set `DATABASE_URL` in the environment / a compose `.env`.

## Render deploy

This repo includes a Blueprint: [`render.yaml`](./render.yaml) (Docker web service + free Postgres).

### One-click Blueprint

1. Push this repo to GitHub (already: `tintwym/home_mart_backend`).
2. [Render Dashboard](https://dashboard.render.com) → **New** → **Blueprint** → select this repo.
3. When prompted (`sync: false` vars), set at least:
   - **`App_Url`** — `https://homemart-api.onrender.com` (use the exact hostname Render assigns)
   - **`App_FrontendUrl`** — your Vercel URL, e.g. `https://homemart-mm.vercel.app`
   - **`Cors_AllowedOrigins`** — same as the frontend origin (or `*` for a quick test)
   - **`FIREBASE_PROJECT_ID`** — e.g. `home-mart-23a2a`
   - Optional Stripe / Cloudinary / Resend / `FIREBASE_CREDENTIALS_JSON` — leave empty if unused
4. Deploy. Health check: `GET /api/health`
5. In the frontend repo, replace `YOUR-RENDER-SERVICE.onrender.com` in `vercel.json` with this service host.

`JWT_KEY` is auto-generated. `DATABASE_URL` comes from the Blueprint Postgres (`homemart-db`). To use **Neon** instead, delete/unlink the Render DB and set `DATABASE_URL` manually in the service env.

### Manual (without Blueprint)

1. New **Web Service** → this repo → **Docker** (`./Dockerfile`)
2. Attach Postgres or set `DATABASE_URL` (Neon)
3. Set the same env vars as in `.env.example` / the table below

| Variable | Example |
|---|---|
| `DATABASE_URL` | Neon or Render Postgres URL |
| `JWT_KEY` | long random secret (≥32 chars) |
| `App_FrontendUrl` / `FRONTEND_URL` | `https://your-app.vercel.app` |
| `App_Url` | `https://your-service.onrender.com` |
| `Cors_AllowedOrigins` | frontend origin |
| `FIREBASE_PROJECT_ID` | Firebase project id (enough for ID-token verify) |
| `FIREBASE_CREDENTIALS_JSON` | Optional service-account JSON (one line) — or `GOOGLE_APPLICATION_CREDENTIALS` |

Frontend (Vite) also needs `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_APP_ID`. Enable Google and Apple in the Firebase Console; for Apple on web, configure an Apple Services ID and return URLs.

## Auth cookies

| Cookie | Notes |
|--------|--------|
| `hm_token` | HttpOnly, SameSite=Lax, 30 days JWT |
| `hm_2fa_pending` | Signed pending 2FA (10 min) |
| `shop_region` / `shop_currency` / `locale` | Preferences |
| `hm_flash_status` / `hm_flash_error` | One-shot flash |

## API surface

- `GET /api/health` — DB connectivity
- `GET /sanctum/csrf-cookie` — 204 no-op for SPA helpers
- `/mapi/*` rewritten to `/api/*`
