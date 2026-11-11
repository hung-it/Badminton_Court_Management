# P2 and regression recovery

Approved scope: #5 court type, #6 booking contact, #7 discounted history,
#10 inventory/type changes, #11 legacy/new passwords, #12 normalized unique
email, #13 positive court prices, and all 47 original failing test cases.

Decisions: positive prices only; an idempotent lower(email) unique index is
authorized. Email collision migration fails without changing data; never merge
accounts. Legacy passwords remain usable; new passwords require 8 characters
and at most 72 UTF-8 bytes. Preserve P1 fixes and financial snapshots.

Use isolated PostgreSQL only. No production deployment or automatic data repair.
Each original failure is tracked in docs/p2-regression-ledger.md. Fix stale
fixtures without relaxing production constraints; fix genuine production defects.

Completion: all mandatory backend and HTTP acceptance cases green with zero skips,
two clean regression runs, frontend tests/builds/browser checks, migration rehearsals.
