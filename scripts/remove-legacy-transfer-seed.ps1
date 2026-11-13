param(
 [Parameter(Mandatory=$true)][string]$Database,
 [string]$Container='bcm-postgres',
 [string]$User='bcm_admin'
)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$backupDirectory=Join-Path $project 'backups'
New-Item -ItemType Directory -Force $backupDirectory | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$name="legacy-transfer-seed-$stamp-$([guid]::NewGuid().ToString('N'))"
$backup=Join-Path $backupDirectory "$name.dump"
& docker exec $Container pg_dump -U $User -d $Database -Fc -f "/tmp/$name.dump"
if($LASTEXITCODE -ne 0){throw 'Backup failed; cleanup was not started'}
& docker cp "${Container}:/tmp/$name.dump" $backup
if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $backup) -or (Get-Item -LiteralPath $backup).Length -eq 0){throw 'Backup copy failed; cleanup was not started'}
& docker exec $Container pg_restore --list "/tmp/$name.dump" *> $null
if($LASTEXITCODE -ne 0){throw 'Backup validation failed; cleanup was not started'}
& docker cp (Join-Path $project 'db/upgrades/remove-legacy-transfer-seed.sql') "${Container}:/tmp/$name.sql"
if($LASTEXITCODE -ne 0){throw 'Cannot copy cleanup script'}
& docker exec $Container psql -U $User -d $Database -v ON_ERROR_STOP=1 -f "/tmp/$name.sql"
if($LASTEXITCODE -ne 0){throw "Cleanup blocked; original data preserved. Backup: $backup"}
Write-Host "Cleanup completed. Full backup: $backup"
