$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$bin = Join-Path $root '.tools/pgsql/bin'
$data = Join-Path $root '.tools/pgdata'
if (!(Test-Path (Join-Path $bin 'pg_ctl.exe'))) { throw 'PostgreSQL portable missing. Install PostgreSQL or prepare .tools/pgsql; see docs/MEMBER2-HANDOVER.md.' }
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Database command failed: $LASTEXITCODE" } }
if (!(Test-Path (Join-Path $data 'PG_VERSION'))) {
    & "$bin/initdb.exe" -D $data -U postgres -A trust --encoding=UTF8 --locale=C
    Check-Exit
}
& "$bin/pg_ctl.exe" -D $data status *> $null
if ($LASTEXITCODE -ne 0) {
    & "$bin/pg_ctl.exe" -D $data -l "$root/.tools/postgres.log" -o '-p 55432 -h 127.0.0.1' start
    Check-Exit
}
$dbArgs = @('-h','127.0.0.1','-p','55432','-U','postgres')
$exists = & "$bin/psql.exe" @dbArgs -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='bcm_member2_test'"
Check-Exit
if (([string]$exists).Trim() -ne '1') { & "$bin/createdb.exe" @dbArgs bcm_member2_test; Check-Exit }
$hasSchema = & "$bin/psql.exe" @dbArgs -d bcm_member2_test -tAc "SELECT to_regclass('public.users') IS NOT NULL"
Check-Exit
if (([string]$hasSchema).Trim() -ne 't') {
    & "$bin/psql.exe" @dbArgs -d bcm_member2_test -v ON_ERROR_STOP=1 -f "$root/db/migration.sql"
    Check-Exit
}
& "$bin/psql.exe" @dbArgs -d bcm_member2_test -v ON_ERROR_STOP=1 -f "$root/db/upgrades/booking-ux-upgrade.sql"
Check-Exit
& "$bin/psql.exe" @dbArgs -d bcm_member2_test -v ON_ERROR_STOP=1 -f "$root/db/upgrades/product-promotions-upgrade.sql"
Check-Exit
& "$bin/psql.exe" @dbArgs -d bcm_member2_test -v ON_ERROR_STOP=1 -f "$root/db/upgrades/court-promotions-upgrade.sql"
Check-Exit
& "$bin/psql.exe" @dbArgs -d bcm_member2_test -v ON_ERROR_STOP=1 -f "$root/db/upgrades/email-identity-upgrade.sql"
Check-Exit
Write-Host 'Database ready: localhost:55432/bcm_member2_test'
