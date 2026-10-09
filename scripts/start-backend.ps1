$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Get-ChildItem 'C:/Program Files/Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
$maven = Join-Path $root '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if (!(Test-Path $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
& $maven -f "$root/backend/pom.xml" spring-boot:run '-Dspring-boot.run.profiles=local'
exit $LASTEXITCODE
