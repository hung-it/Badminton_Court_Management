-- Run in a maintenance window. Keep IDs, credentials, roles and history unchanged.
BEGIN;
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
DO $$
DECLARE collisions TEXT;
BEGIN
    SELECT string_agg(normalized || ' -> ' || ids, '; ' ORDER BY normalized)
    INTO collisions
    FROM (
        SELECT lower(btrim(email)) AS normalized, string_agg(id::text, ', ' ORDER BY id) AS ids
        FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1
    ) duplicate_emails;
    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION 'Email identity collisions require review; no users were changed: %', collisions;
    END IF;
END $$;
UPDATE users SET email=lower(btrim(email)) WHERE email IS DISTINCT FROM lower(btrim(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (lower(email));
COMMIT;
