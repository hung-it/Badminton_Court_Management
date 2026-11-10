param(
    [ValidatePattern('^bcm-demo-[a-z0-9-]+$')][string]$Container = 'bcm-demo-qa-postgres'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$run = [guid]::NewGuid().ToString('N').Substring(0,8)
$database = "bcm_demo_verify_$run"
$output = Join-Path $root ".tools/demo-verify-$run"
New-Item -ItemType Directory -Force $output | Out-Null
function Checked {
    param([string[]]$DockerArgs)
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) { throw "Docker test command failed: $DockerArgs" }
}
function SqlFile([string]$path) {
    Checked @('cp',$path,"${Container}:/tmp/demo-test-$run.sql")
    Checked @('exec',$Container,'psql','-X','-v','ON_ERROR_STOP=1','-U','bcm_admin','-d',$database,'-f',"/tmp/demo-test-$run.sql")
}
function Fingerprint {
    $value = & docker exec $Container psql -X -At -v ON_ERROR_STOP=1 -U bcm_admin -d $database -f /tmp/demo-fingerprint.sql
    if ($LASTEXITCODE -ne 0) { throw 'Cannot fingerprint test database' }
    return ($value -join "`n")
}
# Only a generated, separate database on a QA container is ever mutated.
$ready=$false
for ($attempt=0; $attempt -lt 30; $attempt++) {
    & docker exec $Container pg_isready -h 127.0.0.1 -U bcm_admin *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Seconds 1
}
if (!$ready) { throw 'QA PostgreSQL did not become ready' }
Checked @('exec',$Container,'createdb','-U','bcm_admin',$database)
foreach ($file in @('migration.sql','booking-ux-upgrade.sql','branches-seed.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql')) {
    SqlFile (Join-Path $root "db/$file")
}
Checked @('cp',(Join-Path $root 'db/demo-seed.sql'),"${Container}:/tmp/demo-seed-$run.sql")
$docker = (Get-Command docker.exe -ErrorAction Stop).Source
# Hold the seed lock until both independent seed processes are waiting for it.
$gate = Start-Process $docker -WindowStyle Hidden -PassThru -ArgumentList @('exec',$Container,'psql','-X','-U','bcm_admin','-d',$database,'-c',
    '"BEGIN; SELECT pg_advisory_xact_lock(734281905); SELECT pg_sleep(10); COMMIT;"')
$workers = @()
try {
    $locked = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $state = & docker exec $Container psql -X -At -U bcm_admin -d $database -c "select count(*) from pg_locks where locktype='advisory' and objid=734281905 and granted"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect seed lock' }
        if ($state -eq '1') { $locked=$true; break }
        Start-Sleep -Milliseconds 100
    }
    if (!$locked) { throw 'Seed gate did not acquire advisory lock' }
    foreach ($worker in 1..2) {
        $workers += Start-Process $docker -WindowStyle Hidden -PassThru -ArgumentList @('exec',$Container,'psql','-X','-v','ON_ERROR_STOP=1','-U','bcm_admin','-d',$database,'-f',"/tmp/demo-seed-$run.sql") `
            -RedirectStandardOutput "$output/seed-$worker.log" -RedirectStandardError "$output/seed-$worker-error.log"
    }
    foreach ($process in $workers) {
        if (!$process.WaitForExit(60000)) { throw 'Concurrent seed timed out' }
        if ($process.ExitCode -ne 0) { throw "Concurrent seed failed; inspect $output" }
    }
    $messages = (Get-Content "$output/seed-1-error.log","$output/seed-2-error.log") -join "`n"
    if ($messages -notmatch 'already installed' -or $messages -notmatch 'installed: 15 accounts') {
        throw 'Expected exactly one installation and one no-op'
    }
} finally {
    if (!$gate.WaitForExit(15000)) { throw 'Seed lock holder did not finish' }
}
SqlFile (Join-Path $root 'db/verify-demo-seed.sql')
$tables = @('users','roles','user_roles','customers','staffs','categories','products','suppliers','import_orders',
    'import_order_details','bookings','booking_details','invoices','invoice_details','payment_transactions',
    'promotions','discount_rules','customer_voucher_usage','branches','courts','time_slots')
$fingerprintSql = 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;'
foreach ($table in $tables) {
    $fingerprintSql += "SELECT '$table',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),'')) FROM $table t;"
}
$fingerprintSql += 'COMMIT;'
[IO.File]::WriteAllText("$output/fingerprint.sql",$fingerprintSql)
Checked @('cp',"$output/fingerprint.sql","${Container}:/tmp/demo-fingerprint.sql")
$before = Fingerprint
SqlFile (Join-Path $root 'db/demo-seed.sql')
if ($before -cne (Fingerprint)) { throw 'Rerun changed existing data' }
# Deliberate edits in this disposable DB must survive a subsequent seed rerun.
@'
BEGIN;
UPDATE users SET password_hash='changed-by-test',deleted_at=current_timestamp WHERE email='customer1.demo@bcm.test';
UPDATE products SET price=price+123,stock_quantity=stock_quantity+7 WHERE name='Nước suối 500ml';
UPDATE promotions SET is_active=true WHERE code='DEMO-OFF-1';
COMMIT;
'@ | Set-Content -Encoding utf8 "$output/edits.sql"
SqlFile "$output/edits.sql"
$edited = Fingerprint
SqlFile (Join-Path $root 'db/demo-seed.sql')
if ($edited -cne (Fingerprint)) { throw 'Seed overwrote user edits' }
Write-Host "PASS: concurrent first install, SQL invariants, exact rerun and edit preservation. Test DB: $database; logs: $output"
