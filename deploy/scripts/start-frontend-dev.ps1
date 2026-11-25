# 后台启动前端 dev server（vite，端口 28080）。
#
# 关键点：用 Start-Process 把 vite 拉成独立进程，脚本本身立刻退出。
# 不要用 `& node ... | Out-File` 这种写法——那会让本脚本一直挂在 vite 上，
# 一旦这个隐藏控制台被销毁（窗口关闭、控制台清理），vite 就跟着死。
# 父进程退出后 vite 是孤儿进程，不挂任何终端，关窗口不影响。
#
# 用法（推荐由 WMI 派生，彻底脱离当前 job）：
#   Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
#     CommandLine = 'powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "D:\Desktop\code\metric_code\deploy\scripts\start-frontend-dev.ps1"'
#   }
#
# 注意：本文件必须保存为 UTF-8 带 BOM。PowerShell 5.1 读无 BOM 的 UTF-8 中文
# 会按 GBK 解码，连引号都配不上，直接报“字符串缺少终止符”。

param(
    [string]$Root = 'D:\Desktop\code\metric_code',
    [string]$Node = 'D:\nodejs\node.exe',
    [int]$Port = 28080,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'

$frontend = Join-Path $Root 'frontend'
$logDir = Join-Path $Root 'logs'
$vite = Join-Path $frontend 'node_modules\vite\bin\vite.js'

if (-not (Test-Path -LiteralPath $vite)) { throw "找不到 vite 入口：$vite（先在 frontend 目录执行 npm install）" }
if (-not (Test-Path -LiteralPath $Node)) { throw "找不到 node：$Node" }

$listening = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listening.Count -gt 0) {
    if (-not $Restart) {
        Write-Output "Frontend already listening on $Port (PID $($listening[0].OwningProcess)); use -Restart to rebuild the process."
        exit 0
    }
    foreach ($conn in $listening) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 1
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$process = Start-Process -FilePath $Node -WindowStyle Hidden -PassThru -WorkingDirectory $frontend `
    -ArgumentList @(('"' + $vite + '"'), '--host', '0.0.0.0', '--port', "$Port") `
    -RedirectStandardOutput (Join-Path $logDir 'frontend-dev.out.log') `
    -RedirectStandardError (Join-Path $logDir 'frontend-dev.err.log')
Write-Output "Frontend dev PID: $($process.Id)"
