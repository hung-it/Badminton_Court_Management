$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location "$root/frontend"
if (!(Test-Path 'node_modules')) { npm.cmd ci; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
npm.cmd run dev
exit $LASTEXITCODE
