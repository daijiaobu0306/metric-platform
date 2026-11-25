param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = 'D:\Desktop\code\metric_code'
$envFile = Join-Path $projectRoot 'deploy/config/.env.local'
if (!(Test-Path -LiteralPath $envFile)) {
    throw 'Missing deploy/config/.env.local'
}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
$jarPath = Join-Path $projectRoot 'backend/target/metric-code.jar'
if (!(Test-Path -LiteralPath $jarPath)) {
    throw 'Missing backend JAR'
}
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$backendProcess = Start-Process -FilePath $java `
    -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
    -RedirectStandardError (Join-Path $logDir 'backend.err.log')
Write-Output ("Backend PID: " + $backendProcess.Id)
Write-Output ("Database: " + $env:SPRING_DATASOURCE_URL)
