# Customer Web App

Standalone React 18 + Vite customer application. It runs on port 5174 so the
Web Admin can continue using port 5173. Vite proxies `/api` to the Spring Boot
backend at `http://localhost:8080`.

```powershell
npm ci
npm run dev
```

Open <http://127.0.0.1:5174/courts>. Vite serves the SPA entry point on direct
navigation and refresh at `/courts`. Build with `npm run build`.

Customer tokens are stored separately from the Web Admin session. Booking
confirmation remains disabled until the API exposes a safe customer ID contract.
