param()
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function DockerChecked {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'P1 acceptance Docker command failed' }
}
# The existing suites enforce this exact URL. Use a separate disposable container,
# never the project database or its volume. A conflicting host port fails startup.
$container='bcm-p1-acceptance'
if (-not (& docker ps -a --filter "name=^/$container$" --format '{{.Names}}')) {
    DockerChecked run -d --name $container -p 5433:5432 -e POSTGRES_USER=bcm_root -e POSTGRES_PASSWORD=p1-test-only -e POSTGRES_DB=postgres postgres:15-alpine
} else { DockerChecked start $container | Out-Null }
$ready=$false
for ($i=0; $i -lt 30; $i++) {
    & docker exec $container pg_isready -U bcm_root -d postgres *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Acceptance PostgreSQL did not become ready' }
$role=& docker exec $container psql -U bcm_root -d postgres -Atc "SELECT rolname FROM pg_roles WHERE rolname='bcm_acceptance_owner'"
if ($role -ne 'bcm_acceptance_owner') {
    DockerChecked exec $container psql -U bcm_root -d postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE bcm_acceptance_owner LOGIN PASSWORD 'acceptance-local-only' NOSUPERUSER NOCREATEDB NOCREATEROLE"
}
$database=& docker exec $container psql -U bcm_root -d postgres -Atc "SELECT datname FROM pg_database WHERE datname='bcm_acceptance_test'"
if ($database -ne 'bcm_acceptance_test') {
    DockerChecked exec $container psql -U bcm_root -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE bcm_acceptance_test OWNER bcm_acceptance_owner'
}
DockerChecked exec $container mkdir -p /tmp/p1-acceptance
DockerChecked cp db/migration.sql "${container}:/tmp/p1-acceptance/migration.sql"
$exists=& docker exec $container psql -U bcm_acceptance_owner -d bcm_acceptance_test -Atc "SELECT to_regclass('public.users') IS NOT NULL"
if ($exists -eq 'f') { DockerChecked exec $container psql -U bcm_acceptance_owner -d bcm_acceptance_test -v ON_ERROR_STOP=1 -q -f /tmp/p1-acceptance/migration.sql }
foreach ($file in @('booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql','email-identity-upgrade.sql')) {
    DockerChecked cp "db/upgrades/$file" "${container}:/tmp/p1-acceptance/$file"
    DockerChecked exec $container psql -U bcm_acceptance_owner -d bcm_acceptance_test -v ON_ERROR_STOP=1 -q -f "/tmp/p1-acceptance/$file"
}
$env:BCM_ACCEPTANCE_DB_URL='jdbc:postgresql://127.0.0.1:5433/bcm_acceptance_test'
$env:BCM_POS_GUEST_USER_ID='00000000-0000-4000-8000-00000000b001'
$env:MAVEN_OPTS='-Xms32m -Xmx512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -Djdk.attach.allowAttachSelf=true'
$ErrorActionPreference='Continue'
Push-Location backend
& mvn -q -f pom.xml '-DforkCount=0' '-Dtest=com.bcm.acceptance.*Test' test *> target/p1-acceptance.log
$mavenExit=$LASTEXITCODE
Pop-Location
$ErrorActionPreference='Stop'
if ($mavenExit -ne 0) { Get-Content backend/target/p1-acceptance.log -Tail 25; throw 'P1 acceptance failed' }
Write-Host 'P1 acceptance passed. Log: backend/target/p1-acceptance.log'
