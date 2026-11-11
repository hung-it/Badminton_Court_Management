param()
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Dc { $dockerArgs=$args; & docker @dockerArgs; if($LASTEXITCODE -ne 0){throw 'Docker command failed'} }
$target=Join-Path (Get-Location) 'backend/target'
New-Item -ItemType Directory -Force $target | Out-Null
# Fixed target; neither the caller nor legacy BCM_TEST_DB_URL can select the main DB.
if($env:BCM_ACCEPTANCE_DB_URL -and $env:BCM_ACCEPTANCE_DB_URL -ne 'jdbc:postgresql://127.0.0.1:5433/bcm_acceptance_test'){throw 'Refusing non-acceptance URL'}
$env:BCM_ACCEPTANCE_DB_URL='jdbc:postgresql://127.0.0.1:5433/bcm_acceptance_test'
$mainSql='BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;'
foreach($table in @('users','customers','bookings','booking_details','payment_transactions','invoices','invoice_details','products','courts','time_slots','promotions','discount_rules','roles','user_roles','staffs','categories','branches','customer_voucher_usage','import_orders','import_order_details')) {
 $mainSql+="SELECT '$table',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),'')) FROM $table t;"
}
$mainSql+='COMMIT;'
[IO.File]::WriteAllText("$target/acceptance-main-fingerprint.sql",$mainSql)
Dc cp "$target/acceptance-main-fingerprint.sql" bcm-postgres:/tmp/acceptance-main-fingerprint.sql
function Fingerprint {
 $output=& docker exec bcm-postgres sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 -f /tmp/acceptance-main-fingerprint.sql'
 if($LASTEXITCODE -ne 0){throw 'Read-only fingerprint failed'}
 return $output
}
$before=Fingerprint
$before | Set-Content "$target/acceptance-main-before.log"
try {
 $setup=@'
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='bcm_acceptance_owner') THEN
  CREATE ROLE bcm_acceptance_owner LOGIN PASSWORD 'acceptance-local-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='bcm_acceptance_owner' AND (rolsuper OR rolcreatedb OR rolcreaterole)) THEN RAISE EXCEPTION 'Unsafe acceptance role'; END IF;
END $$;
SELECT 'CREATE DATABASE bcm_acceptance_test OWNER bcm_acceptance_owner' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='bcm_acceptance_test')
\gexec
'@
 [IO.File]::WriteAllText("$target/acceptance-role.sql",$setup)
 Dc cp "$target/acceptance-role.sql" bcm-postgres:/tmp/acceptance-role.sql
 Dc exec bcm-postgres sh -c 'psql -X -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -f /tmp/acceptance-role.sql'
 # Copy files explicitly: copying the directory again nests db/ under an existing
 # destination and otherwise leaves stale migrations at the paths psql executes.
 Dc exec bcm-postgres mkdir -p /tmp/acceptance-db
 foreach($sqlFile in @('migration.sql','booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql')) {
  $sourceFile=if($sqlFile -eq 'migration.sql'){"db/$sqlFile"}else{"db/upgrades/$sqlFile"}
  Dc cp $sourceFile "bcm-postgres:/tmp/acceptance-db/$sqlFile"
 }
 $check=& docker exec -e PGPASSWORD=acceptance-local-only bcm-postgres psql -h 127.0.0.1 -U bcm_acceptance_owner -d bcm_acceptance_test -Atc "select current_database()||'|'||current_user"
 if($LASTEXITCODE -ne 0 -or $check -ne 'bcm_acceptance_test|bcm_acceptance_owner'){throw 'Wrong acceptance target'}
 $schema=& docker exec -e PGPASSWORD=acceptance-local-only bcm-postgres psql -h 127.0.0.1 -U bcm_acceptance_owner -d bcm_acceptance_test -Atc "select count(*) from information_schema.tables where table_schema='public'"
 if($LASTEXITCODE -ne 0){throw 'Schema inspection failed'}
 if(([string]$schema).Trim() -eq '0'){Dc exec -e PGPASSWORD=acceptance-local-only bcm-postgres psql -h 127.0.0.1 -U bcm_acceptance_owner -d bcm_acceptance_test -v ON_ERROR_STOP=1 -f /tmp/acceptance-db/migration.sql}
 foreach($file in @('booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql')) {
  Dc exec -e PGPASSWORD=acceptance-local-only bcm-postgres psql -h 127.0.0.1 -U bcm_acceptance_owner -d bcm_acceptance_test -v ON_ERROR_STOP=1 -f "/tmp/acceptance-db/$file"
 }
 $env:JAVA_HOME=(Resolve-Path backend/target/phase1-tools/jdk-17.0.20.1+1).Path
 $repo=Join-Path $target 'phase1-m2'
 $ErrorActionPreference='Continue'
 & backend/target/phase1-tools/apache-maven-3.9.9/bin/mvn.cmd "-Dmaven.repo.local=$repo" '-Dtest=com.bcm.acceptance.*Test' '-Djunit.jupiter.execution.parallel.enabled=false' -f backend/pom.xml test *> "$target/feature-acceptance.log"
 $mavenExit=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Get-Content "$target/feature-acceptance.log" | Select-String 'Tests run:|BUILD SUCCESS|BUILD FAILURE'
 if($mavenExit -ne 0){throw 'Acceptance failed: backend/target/feature-acceptance.log'}
} finally {
 $after=Fingerprint
 $after | Set-Content "$target/acceptance-main-after.log"
 if(Compare-Object $before $after){throw 'Main fingerprint changed; inspect logs'}
 Write-Host 'Main database fingerprint unchanged.'
}
