# 仅启动后端（前端已单独运行）。会话外常驻：由 WMI 派生，避免随工具任务结束被回收。
$ErrorActionPreference = 'Stop'
Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:https_proxy -ErrorAction SilentlyContinue
Remove-Item Env:HTTP_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:http_proxy -ErrorAction SilentlyContinue
Remove-Item Env:ALL_PROXY -ErrorAction SilentlyContinue
Remove-Item Env:all_proxy -ErrorAction SilentlyContinue

$projectRoot = 'D:\Desktop\code\metric_code'
$envFile = Join-Path $projectRoot 'deploy\config\.env.local'
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$jarPath = Join-Path $projectRoot 'backend\target\metric-code.jar'
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$p = Start-Process -FilePath (Get-Command java.exe).Source `
    -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
    -RedirectStandardError (Join-Path $logDir 'backend.err.log')
Write-Output "Backend PID: $($p.Id)"
