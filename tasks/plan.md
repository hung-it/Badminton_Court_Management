# P1 business invariants

Approved scope: refresh identity (#1), branch cards (#2), court deletion locks (#3),
slot mutation locks (#4), voucher reservations (#8), configured guest identity (#9).

Decisions: reject legacy refresh tokens without uid; existing access tokens expire
normally. Configure guest users.id per environment; no schema migration or data
rewrite. Never test against the project database. No production deployment in this
implementation; provide deployment instructions and a test-environment rehearsal.

Implementation order and acceptance checks are tracked in [todo.md](todo.md).
