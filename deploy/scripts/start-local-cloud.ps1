param([switch]$SkipBuild, [switch]$Restart)

# 使用云端数据库时的本地一键启动脚本。
#   - 配置来源：deploy/config/.env.local（已被 Git 忽略，拉代码不会覆盖）
#   - 与 start-local.ps1 的区别：不做“本地采集开发库”专用校验，可用于云端库
# 用法：
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/start-local-cloud.ps1
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/start-local-cloud.ps1 -SkipBuild
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/start-local-cloud.ps1 -Restart
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = Join-Path $projectRoot 'deploy/config/.env.local'
if (!(Test-Path -LiteralPath $envFile)) {
    throw 'Missing deploy/config/.env.local; see doc/本地启动说明.md'
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
            throw "Port $port is occupied. Stop the existing project process before restarting (or pass -Restart)."
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
        -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
        -RedirectStandardError (Join-Path $logDir 'backend.err.log')
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
        -ArgumentList @(('"' + $vitePath + '"'), '--host', '127.0.0.1', '--strictPort') `
        -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir 'frontend.out.log') `
        -RedirectStandardError (Join-Path $logDir 'frontend.err.log')
    Write-Output "Backend PID: $($backendProcess.Id); frontend PID: $($frontendProcess.Id)"
    Write-Output "Database: $($env:SPRING_DATASOURCE_URL)"
    Write-Output 'Open http://127.0.0.1:28080/metric-code/login?nosso=1'
} finally {
    Pop-Location
}
