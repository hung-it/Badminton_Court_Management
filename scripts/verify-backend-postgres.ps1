param([int]$Runs = 2)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$testContainer = 'bcm-invoice-regression-tests-v2'
$testPort = 55437
function DockerChecked {
 $Arguments=$args
 & docker @Arguments
 if ($LASTEXITCODE -ne 0) { throw "Docker failed: $Arguments" }
}
$existing = & docker ps -a --filter "name=^/$testContainer$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect test Docker container' }
if (!$existing) {
 DockerChecked run -d --name $testContainer -p "${testPort}:5432" -e POSTGRES_USER=bcm_test -e POSTGRES_PASSWORD=regression-test-only -e POSTGRES_DB=bcm_regression_test postgres:15-alpine
} else { DockerChecked start $testContainer }
$ready=$false
for($attempt=0;$attempt -lt 30;$attempt++) {
 & docker exec $testContainer pg_isready -h 127.0.0.1 -U bcm_test -d bcm_regression_test *> $null
 if($LASTEXITCODE -eq 0){$ready=$true;break}
 Start-Sleep -Seconds 1
}
if(!$ready){throw 'Test PostgreSQL did not become ready'}
DockerChecked cp db "${testContainer}:/tmp/db"
$schemaExists = & docker exec $testContainer psql -U bcm_test -d bcm_regression_test -Atc "select to_regclass('public.users') is not null"
if($LASTEXITCODE -ne 0){throw 'Cannot inspect test schema'}
if($schemaExists -eq 'f') { DockerChecked exec $testContainer psql -U bcm_test -d bcm_regression_test -v ON_ERROR_STOP=1 -f /tmp/db/migration.sql }
foreach($script in @('upgrades/booking-ux-upgrade.sql','upgrades/product-promotions-upgrade.sql','upgrades/court-promotions-upgrade.sql','upgrades/email-identity-upgrade.sql')) {
 DockerChecked exec $testContainer psql -U bcm_test -d bcm_regression_test -v ON_ERROR_STOP=1 -f "/tmp/db/$script"
}
$env:BCM_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${testPort}/bcm_regression_test"
$env:BCM_TEST_DB_USERNAME='bcm_test'
$env:BCM_TEST_DB_PASSWORD='regression-test-only'
$env:JAVA_HOME=(Resolve-Path backend/target/phase1-tools/jdk-17.0.20.1+1).Path
$mavenRepo=Join-Path $repoRoot 'backend/target/phase1-m2'
for($run=1;$run -le $Runs;$run++) {
 $ErrorActionPreference='Continue'
 & backend/target/phase1-tools/apache-maven-3.9.9/bin/mvn.cmd "-Dmaven.repo.local=$mavenRepo" -f backend/pom.xml verify *> "backend/target/regression-verify-$run.log"
 $mavenExit=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 if($mavenExit -ne 0){throw "Verify $run failed; inspect backend/target/regression-verify-$run.log"}
}
