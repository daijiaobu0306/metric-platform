param([switch]$SkipBuild, [switch]$Restart, [string]$EnvFile)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = if ($EnvFile) { (Resolve-Path -LiteralPath $EnvFile).Path } else { Join-Path $projectRoot 'deploy/config/.env.local' }
if (!(Test-Path -LiteralPath $envFile)) {
    throw 'Missing deploy/config/.env.local; see doc/数据采集改造1008/01_本地部署说明与状态.md'
}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
foreach ($port in 18080, 28080) {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($processId in @($listeners.OwningProcess | Select-Object -Unique)) {
        if (!$processId) { continue }
        $running = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
        if (!$Restart -or $running.CommandLine -notmatch [regex]::Escape($projectRoot) -or
            $running.ExecutablePath -notmatch '(java|node)\.exe$') {
            throw "Port $port is occupied. Stop the existing project process before restarting."
        }
        Stop-Process -Id $processId
    }
}
Push-Location $projectRoot
try {
    if (!$SkipBuild) {
        & mvn.cmd -B -f backend/pom.xml -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
    }
    $jarPath = Join-Path $projectRoot 'backend/target/metric-code.jar'
    $vitePath = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
    if (!(Test-Path $jarPath) -or !(Test-Path $vitePath)) {
        throw 'Missing backend JAR or frontend dependencies; build backend and run npm ci in frontend.'
    }
    $logDir = Join-Path $projectRoot 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $backendProcess = Start-Process -FilePath (Get-Command java.exe).Source `
        -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir 'collection-backend.out.log') `
        -RedirectStandardError (Join-Path $logDir 'collection-backend.err.log')
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
        -ArgumentList @(('"' + $vitePath + '"'), '--host', '127.0.0.1', '--strictPort') `
        -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir 'collection-frontend.out.log') `
        -RedirectStandardError (Join-Path $logDir 'collection-frontend.err.log')
    Write-Output "Backend PID: $($backendProcess.Id); frontend PID: $($frontendProcess.Id)"
    Write-Output 'Processes launched. Check logs for startup completion.'
    Write-Output 'Open http://127.0.0.1:28080/metric-code/login?nosso=1'
} finally {
    Pop-Location
}
