# P1 implementation

- [x] Establish isolated PostgreSQL baseline and record existing failures.
- [x] Bind refresh tokens to uid; reject legacy tokens with HTTP 401; test email reuse.
- [x] Configure and validate guest identity; remove phone-based protection; test spoofed guests.
- [x] Coordinate Court deletion and TimeSlot mutations with booking locks; test both transaction orders.
- [x] Retain voucher reservations for pending payments until terminal state; test grace and concurrency.
- [x] Group court cards by stable branch key; test missing first court and remaining booking choices.
- [x] Run PostgreSQL regression, 10 concurrency repetitions, both frontend builds and browser checks.
- [x] Review diff and document rollout, environment variables, test results and limitations.

Results: 224 focused backend cases, 48 Node cases, browser fixture integration and
both frontend builds passed. Broad regression has 47 failed cases, all individually
reproduced on original commit f7d48ea; no new failed cases. Project-wide release gate
remains red. See [verification and rollout](../docs/p1-business-invariants.md).
