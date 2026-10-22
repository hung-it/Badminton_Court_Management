# Customer Web App

React 18 + Vite customer application, with the Home/Login/Register pages from
develop integrated with the Booking Engine screens. Port 5174 is reserved for
this app; Web Admin continues using 5173. Vite proxies `/api` to Spring Boot
at `http://localhost:8080`. Set `BCM_API_PROXY_TARGET` when testing another backend.

```powershell
npm ci
npm run dev
```

Open <http://127.0.0.1:5174/>. Routes: `/`, `/login`, `/register`, `/courts`,
`/guide`, `/contact`, `/checkout`, `/payment-result`. `/booking` preserves its
search parameters and redirects to `/courts`; `/my-bookings` currently opens
the upcoming-booking summary on `/courts`. `/profile` remains a placeholder.
Build with `npm run build`. Production hosting must serve `index.html` for SPA
routes and proxy `/api` (the Vite dev proxy is not part of the build).

All customer pages use `lib/apiClient.js` and `tokenStore`, separately from
Web Admin. Access tokens stay in sessionStorage. Remembered refresh tokens and
profiles use localStorage; otherwise both stay in sessionStorage. A remembered
session is restored through the existing refresh API. Concurrent 401s share one
refresh. Logout clears the customer session and booking draft.

Checkout resolves `customers.id` with authenticated `GET /api/bookings/customer-profile`
before creating a booking. Retrying/reloading an existing draft reads its owned
booking and snapshot price, without treating its own held slot as unavailable.
VNPay Sandbox credentials and return URL must be configured on the backend;
missing configuration returns 503, preserves the booking and creates no payment.
Set the merchant return URL to the public `/payment-result` URL for this web app.
Only verified server payment history can produce a successful payment result.

Browser regression: `node scripts/verify-booking-web.mjs` from repository root,
against a separate backend/database initialized from `db/migration.sql`, with
VNPay credentials unset. It creates test accounts/bookings. Set
`BCM_WEB_TEST_URL`, `PLAYWRIGHT_MODULE` (default `playwright/test`) and
`BCM_BROWSER_CHANNEL` (default `msedge`) for your environment. Screenshots are
written under `.tools/web-review/`.
