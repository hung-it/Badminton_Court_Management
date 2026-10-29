$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $root '.tools/shared-docker'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else { Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe' }
if (!(Test-Path -LiteralPath $docker)) { throw 'Cần cài Docker Desktop và mở Docker Engine trước.' }
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$composeArgs = @('compose', '-p', 'develop-docker', '-f', (Join-Path $root 'docker-compose.yml'))
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
# Mount init chỉ chạy cho volume mới. Volume đang có cần các migration cộng thêm, chạy lại an toàn.
foreach ($migration in @('member2-upgrade.sql','member2-payment-method-upgrade.sql','product-promotions-upgrade.sql','court-promotions-upgrade.sql','catalog-seed.sql')) {
    & $docker cp (Join-Path $root "db/$migration") "bcm-postgres:/tmp/bcm-$migration"
    Assert-DockerExit "Không copy được $migration."
    & $docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f "/tmp/bcm-$migration"
    Assert-DockerExit "Migration $migration thất bại; chưa chuyển giao diện. Kiểm tra log và bản sao lưu DB."
}
& $docker @composeArgs up -d --build backend
Assert-DockerExit 'Không build/khởi động được backend chung.'
$apiTarget = 'http://127.0.0.1:8080'
$backendReady = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    if (Test-Api "$apiTarget/api/health") { $backendReady = $true; break }
    Start-Sleep -Seconds 2
}
if (!$backendReady) { throw 'Backend Docker chưa healthy; chưa chuyển giao diện. Kiểm tra docker logs bcm-backend.' }
$env:BCM_API_PROXY_TARGET = $apiTarget
foreach ($site in @(@{Folder='web-admin';Port=5173}, @{Folder='web-app';Port=5174})) {
    $siteRoot = Join-Path $root $site.Folder
    if (!(Test-Path -LiteralPath (Join-Path $siteRoot 'node_modules'))) {
        & $npm --prefix $siteRoot ci
        if ($LASTEXITCODE -ne 0) { throw "Không cài được dependency $($site.Folder)." }
    }
    $targetFile = Join-Path $runtimeDir "$($site.Folder).target"
    $owners = @(Get-NetTCPConnection -LocalPort $site.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    $sitePattern = [regex]::Escape(($siteRoot -replace '\\','/') + '/') + '.*vite'
    foreach ($ownerId in $owners) {
        $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId"
        if (!$ownedProcess -or ($ownedProcess.CommandLine -replace '\\','/') -notmatch $sitePattern) {
            throw "Cổng $($site.Port) đang được tiến trình ngoài dự án sử dụng; không dừng tiến trình đó."
        }
    }
    $alreadyShared = $owners.Count -gt 0 -and (Test-Path -LiteralPath $targetFile) -and ((Get-Content -LiteralPath $targetFile -Raw).Trim() -eq $apiTarget) -and (Test-Api "http://127.0.0.1:$($site.Port)/api/health")
    if ($alreadyShared) { Write-Host "$($site.Folder) đã dùng backend Docker chung."; continue }
    foreach ($ownerId in $owners) { Stop-Process -Id $ownerId }
    Start-Process cmd.exe -WindowStyle Hidden -WorkingDirectory $siteRoot -ArgumentList '/c npm.cmd run dev' -RedirectStandardOutput (Join-Path $runtimeDir "$($site.Folder).log") -RedirectStandardError (Join-Path $runtimeDir "$($site.Folder)-error.log") | Out-Null
    [System.IO.File]::WriteAllText($targetFile, $apiTarget)
    $siteReady = $false
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        if (Test-Api "http://127.0.0.1:$($site.Port)/api/health") { $siteReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$siteReady) { throw "$($site.Folder) chưa sẵn sàng. Xem log trong $runtimeDir." }
}
Write-Host 'Web Admin: http://127.0.0.1:5173/ | Web User: http://127.0.0.1:5174/'
Write-Host 'Cả hai dùng Docker API 8080 và DB badminton_court_db; không dùng DB demo native 55432.'
