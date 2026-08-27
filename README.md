# Home Mart Backend (Spring Boot 4.1 / Java 21)

JSON API for the **Next.js storefront** (`frontend/`), **admin dashboard** (`admin/`),
and mobile apps. Deploy to **Render** (or Railway/Fly).

Inertia page controllers may still exist for legacy clients, but the Next.js apps
use **`/api/*` only** with Bearer JWT.

## What it owns

- JSON API under `/api` (+ `/mapi` alias for mobile)
- Admin API under `/api/admin/**` (`ROLE_ADMIN`)
- Schema via Hibernate (`ddl-auto=update`) + optional category/listing/admin seeders
- Auth: Bearer JWT (preferred) + optional cookie `hm_token`
- Firebase Google / Apple (`POST /api/auth/firebase`)
- Stripe, Cloudinary, TOTP 2FA, WebAuthn passkeys
- Notifications at `GET /api/notifications`
- Logout at `POST /api/logout`

## Requirements

- Java 21+
- Maven 3.9+
- PostgreSQL 14+

## Local development

```bash
cd backend
cp .env.example .env   # if present
# Set DATABASE_URL (or SPRING_DATASOURCE_*) and JWT_KEY at minimum
# Local profile seeds demo data + admin user

set -a && source .env && set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# http://localhost:5199
```

Then:

- Storefront: `npm run dev` in `frontend/` → http://localhost:3000
- Admin: `npm run dev` in `admin/` → http://localhost:3001

### Seeded admin

With local seed / `ADMIN_EMAIL`:

| Field | Default |
|---|---|
| Email | `admin@homemart.com` |
| Password | `admin12345` |

Override with `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NAME`.

## CORS

Local profile allows `http://localhost:3000,http://localhost:3001`.

Production: set `Cors_AllowedOrigins` to your Vercel storefront + admin origins
(comma-separated). Do not use `*` if you need credentialed cookies.

## Admin API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/admin/me` | Current admin user |
| GET | `/api/admin/stats` | `{ users, listings, orders }` |
| GET/PATCH | `/api/admin/users`, `/api/admin/users/{id}` | role, seller_type, banned |
| GET/PATCH/DELETE | `/api/admin/listings`… | moderate listings |
| GET | `/api/admin/orders`… | inspect orders |
| CRUD | `/api/admin/categories`… | categories + subcategories |

All `/api/admin/**` require JWT with `ROLE_ADMIN`.

## Render deploy

See [`render.yaml`](./render.yaml). Set at least:

- `App_Url`, `App_FrontendUrl`, `Cors_AllowedOrigins` (include storefront + admin)
- `JWT_KEY`, `DATABASE_URL`
- Optional: `ADMIN_EMAIL` / `ADMIN_PASSWORD`, Firebase, Stripe, Cloudinary

Health: `GET /api/health`
