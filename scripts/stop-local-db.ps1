$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$root/.tools/pgsql/bin/pg_ctl.exe" -D "$root/.tools/pgdata" stop -m fast
exit $LASTEXITCODE
