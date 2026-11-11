param([string]$Tests = '', [int]$ConcurrencyRuns = 0)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($env:SPRING_DATASOURCE_URL -or $env:SPRING_DATASOURCE_USERNAME -or $env:SPRING_DATASOURCE_PASSWORD) {
    throw 'Unset SPRING_DATASOURCE_* before running tests; those overrides bypass test profiles'
}
function DockerChecked {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "P1 test Docker command failed" }
}
$container = 'bcm-p1-tests'
if (-not (& docker ps -a --filter "name=^/$container$" --format '{{.Names}}')) {
    DockerChecked run -d --name $container -p 55437:5432 -e POSTGRES_USER=bcm_test -e POSTGRES_PASSWORD=p1-test-only -e POSTGRES_DB=bcm_p1_work postgres:15-alpine
} else { DockerChecked start $container | Out-Null }
$ready = $false
for ($i=0; $i -lt 30; $i++) {
    & docker exec $container pg_isready -U bcm_test -d postgres *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Test PostgreSQL did not become ready' }
$database = & docker exec $container psql -U bcm_test -d postgres -Atc "SELECT datname FROM pg_database WHERE datname='bcm_p1_work'"
if ($database -ne 'bcm_p1_work') { DockerChecked exec $container psql -U bcm_test -d postgres -c 'CREATE DATABASE bcm_p1_work' }
DockerChecked exec $container mkdir -p /tmp/p1-db/upgrades
DockerChecked cp db/migration.sql "${container}:/tmp/p1-db/migration.sql"
foreach ($file in @('booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql')) {
    DockerChecked cp "db/upgrades/$file" "${container}:/tmp/p1-db/upgrades/$file"
}
$exists = & docker exec $container psql -U bcm_test -d bcm_p1_work -Atc "SELECT to_regclass('public.users') IS NOT NULL"
if ($exists -eq 'f') {
    DockerChecked exec $container psql -U bcm_test -d bcm_p1_work -v ON_ERROR_STOP=1 -q -f /tmp/p1-db/migration.sql
}
foreach ($file in @('booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql')) {
    DockerChecked exec $container psql -U bcm_test -d bcm_p1_work -v ON_ERROR_STOP=1 -q -f "/tmp/p1-db/upgrades/$file"
}
DockerChecked exec $container psql -U bcm_test -d bcm_p1_work -v ON_ERROR_STOP=1 -q -c "INSERT INTO users(id,email,password_hash,full_name,phone) VALUES ('00000000-0000-4000-8000-00000000b001','guest@bcm.com','!','P1 test guest','0000000000') ON CONFLICT(id) DO NOTHING; INSERT INTO customers(id,user_id,full_name,phone) VALUES ('00000000-0000-4000-8000-00000000b002','00000000-0000-4000-8000-00000000b001','P1 test guest','0000000000') ON CONFLICT(user_id) DO NOTHING; INSERT INTO user_roles(user_id,role_id) SELECT '00000000-0000-4000-8000-00000000b001',id FROM roles WHERE role_name='CUSTOMER' ON CONFLICT DO NOTHING;"
# Only this disposable database is selected. Main/local application ports are never test targets.
$url = 'jdbc:postgresql://127.0.0.1:55437/bcm_p1_work'
foreach ($prefix in @('BCM_TEST_DB','BCM_INVOICE_DEV_DB','BCM_PROMOTION_TEST_DB')) {
    [Environment]::SetEnvironmentVariable("${prefix}_URL",$url,'Process')
    [Environment]::SetEnvironmentVariable("${prefix}_USERNAME",'bcm_test','Process')
    [Environment]::SetEnvironmentVariable("${prefix}_PASSWORD",'p1-test-only','Process')
}
$env:DB_URL=$url; $env:DB_USER='bcm_test'; $env:DB_PASSWORD='p1-test-only'
$env:BCM_POS_GUEST_USER_ID='00000000-0000-4000-8000-00000000b001'
$env:MAVEN_OPTS='-Xms32m -Xmx512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -Djdk.attach.allowAttachSelf=true'
if (-not $Tests) {
    # A different checkout can leave deleted tests in target/test-classes. Never run those as regression evidence.
    $ErrorActionPreference='Continue'
    Push-Location backend
    & mvn -q -f pom.xml clean *> (Join-Path $env:TEMP 'bcm-p1-clean.log')
    $cleanExit=$LASTEXITCODE
    Pop-Location
    $ErrorActionPreference='Stop'
    if ($cleanExit -ne 0) { throw 'P1 clean failed; inspect bcm-p1-clean.log in TEMP' }
}
New-Item -ItemType Directory -Force backend/target | Out-Null
$mavenArgs=@('-q','-f','pom.xml','-DforkCount=0')
if ($Tests) { $mavenArgs += "-Dtest=$Tests" }
# Acceptance has a separately enforced database URL; run it with its own fixture script.
else { $mavenArgs += '-Dtest=!com.bcm.acceptance.*Test,!LegacyPaymentCompatibilityPostgresTest' }
$ErrorActionPreference='Continue'
Push-Location backend
& mvn @mavenArgs verify *> target/p1-verify.log
$mavenExit=$LASTEXITCODE
Pop-Location
$ErrorActionPreference='Stop'
if ($mavenExit -ne 0) { Get-Content backend/target/p1-verify.log -Tail 35; throw 'P1 verification failed; see backend/target/p1-verify.log' }
for ($run=1; $run -le $ConcurrencyRuns; $run++) {
    $ErrorActionPreference='Continue'
    Push-Location backend
    & mvn -q -f pom.xml '-DforkCount=0' '-Dtest=P1MasterDataConcurrencyPostgresTest,BookingConcurrencyPostgresTest,BookingPromotionPostgresTest' test *> "target/p1-concurrency-$run.log"
    $mavenExit=$LASTEXITCODE
    Pop-Location
    $ErrorActionPreference='Stop'
    if ($mavenExit -ne 0) { throw "Concurrency run $run failed" }
}
Write-Host 'P1 verification passed. Reports: backend/target/surefire-reports'
