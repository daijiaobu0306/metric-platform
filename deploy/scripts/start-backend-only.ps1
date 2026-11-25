# Restart backend only (keep vite frontend running). Launch via WMI so the
# process survives the current task session.
param([switch]$Restart)

$ErrorActionPreference = 'Stop'

# Clear proxy vars (case variants) to avoid duplicate-key errors in PS env dict.
foreach ($k in 'HTTPS_PROXY','HTTP_PROXY','https_proxy','http_proxy','ALL_PROXY','all_proxy','NO_PROXY','no_proxy') {
    Remove-Item "Env:$k" -ErrorAction SilentlyContinue
}

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

if ($Restart) {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue
    foreach ($processId in @($listeners.OwningProcess | Select-Object -Unique)) {
        if ($processId) { Stop-Process -Id $processId -Force }
    }
}

$jarPath = Join-Path $projectRoot 'backend/target/metric-code.jar'
if (!(Test-Path $jarPath)) { throw 'Missing backend JAR; run mvn package first.' }

$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$backendProcess = Start-Process -FilePath (Get-Command java.exe).Source `
    -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
    -RedirectStandardError (Join-Path $logDir 'backend.err.log')

Write-Output "Backend PID: $($backendProcess.Id)"
Write-Output "Database: $($env:SPRING_DATASOURCE_URL)"
