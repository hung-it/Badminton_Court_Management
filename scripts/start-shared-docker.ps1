$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = [Console]::OutputEncoding
$root = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $root '.tools/shared-docker'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else { Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe' }
if (!(Test-Path -LiteralPath $docker)) { throw 'Cần cài Docker Desktop và mở Docker Engine trước.' }
$composeArgs = @('compose', '-p', 'badminton_court_management', '-f', (Join-Path $root 'docker-compose.yml'))
function Assert-DockerExit([string]$message) {
    if ($LASTEXITCODE -ne 0) { throw $message }
}
function Test-Api([string]$url) {
    try { return (Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { return $false }
}
& $docker info --format '{{.ServerVersion}}'
Assert-DockerExit 'Mở Docker Desktop và chờ Engine Running.'
& $docker @composeArgs config --quiet
Assert-DockerExit 'Cấu hình Docker Compose không hợp lệ.'
& $docker volume create badminton_court_management_postgres_data | Out-Null
Assert-DockerExit 'Không tạo/đọc được volume PostgreSQL.'
& $docker @composeArgs up -d postgres
Assert-DockerExit 'Không khởi động được PostgreSQL.'
$dbReady = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    # PID 1 chỉ chuyển thành postgres sau khi toàn bộ init SQL của volume mới đã chạy xong.
    $dbProcess = & $docker exec bcm-postgres cat /proc/1/comm 2>$null
    $dbState = & $docker inspect bcm-postgres --format '{{.State.Health.Status}}'
    if (([string]$dbProcess).Trim() -eq 'postgres' -and ([string]$dbState).Trim() -eq 'healthy') { $dbReady = $true; break }
    Start-Sleep -Seconds 2
}
if (!$dbReady) { throw 'PostgreSQL chưa sẵn sàng. Kiểm tra docker logs bcm-postgres; không xóa volume.' }
# Preserve an archive before applying upgrades to an existing development volume.
$backupFolder = Join-Path $root 'backups'
New-Item -ItemType Directory -Path $backupFolder -Force | Out-Null
$backupName = 'shared-before-upgrade-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump'
& $docker exec bcm-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/bcm-before-upgrade.dump'
Assert-DockerExit 'Database backup failed; no upgrades applied.'
& $docker exec bcm-postgres pg_restore --list /tmp/bcm-before-upgrade.dump | Out-Null
Assert-DockerExit 'Backup archive validation failed; no upgrades applied.'
& $docker cp bcm-postgres:/tmp/bcm-before-upgrade.dump (Join-Path $backupFolder $backupName)
Assert-DockerExit 'Cannot preserve backup on host; no upgrades applied.'
# Mount init chỉ chạy cho volume mới. Volume đang có cần các migration cộng thêm, chạy lại an toàn.
foreach ($migration in @('booking-ux-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql','email-identity-upgrade.sql')) {
    & $docker cp (Join-Path $root "db/upgrades/$migration") "bcm-postgres:/tmp/bcm-$migration"
    Assert-DockerExit "Không copy được $migration."
    $upgradeCommand = 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/bcm-' + $migration
    & $docker exec bcm-postgres sh -c $upgradeCommand
    Assert-DockerExit "Migration $migration thất bại; chưa chuyển giao diện. Kiểm tra log và bản sao lưu DB."
}
& $docker cp (Join-Path $root 'db/checks/verify-runtime.sql') 'bcm-postgres:/tmp/bcm-verify-runtime.sql'
Assert-DockerExit 'Không copy được kiểm tra dữ liệu runtime.'
& $docker exec bcm-postgres psql -X -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f /tmp/bcm-verify-runtime.sql
Assert-DockerExit 'Dữ liệu DB gốc chưa qua kiểm tra nghiệp vụ; chưa khởi động backend.'
& $docker @composeArgs up -d --build backend
Assert-DockerExit 'Không build/khởi động được backend chung.'
$apiTarget = 'http://127.0.0.1:8080'
$backendReady = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    if (Test-Api "$apiTarget/api/health") { $backendReady = $true; break }
    Start-Sleep -Seconds 2
}
if (!$backendReady) { throw 'Backend Docker chưa healthy; chưa chuyển giao diện. Kiểm tra docker logs bcm-backend.' }
# Both frontends run in the same Compose project and proxy to this backend.
& $docker @composeArgs up -d --build web-admin web-user
Assert-DockerExit 'Không build/khởi động được giao diện Docker.'
foreach ($port in @(5173,5174)) {
    $siteReady=$false
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        if (Test-Api "http://127.0.0.1:$port/api/health") { $siteReady=$true; break }
        Start-Sleep -Seconds 2
    }
    if (!$siteReady) { throw "Giao diện Docker cổng $port chưa kết nối được API." }
}
Write-Host 'Web Admin: http://127.0.0.1:5173/ | Web App: http://127.0.0.1:5174/'
Write-Host 'Docker API: http://127.0.0.1:8080/api/health | DB duy nhất: badminton_court_db (5433).'
