# 一键把本地全套服务拉到后台：后端 / 前端 dev / 采集 Worker / Mock 生成器。
#
# 目标：全部进程都没有可见窗口，关掉任何命令行窗口都不影响它们。
# 每个子启动脚本内部都用 Start-Process -WindowStyle Hidden 拉起服务并立刻退出，
# 服务进程成为孤儿进程，不挂在任何终端上。
#
# 用法（自己开个窗口跑完这一条就能关窗口）：
#   Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
#     CommandLine = 'powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "D:\Desktop\code\metric_code\deploy\scripts\start-all-background.ps1"'
#   }
#
# 想只重启某一个：加 -Restart 会把已在跑的停掉再起（后端/前端）。
# Mock 生成器与 Worker 已有幂等判断，未运行才启动。

param(
    [switch]$Restart,
    [string]$MockRoot = 'D:\Desktop\code\metric-mock'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scripts = $PSScriptRoot

function Test-Port([int]$Port) {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count -gt 0
}

function Find-Process([string]$Pattern) {
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$Pattern*" }
}

$report = New-Object System.Collections.Generic.List[string]

# ---------- 后端（18080）----------
if ((Test-Port 18080) -and -not $Restart) {
    $report.Add('backend    : already listening on 18080')
} else {
    if ($Restart -and (Test-Port 18080)) {
        Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique |
            ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 3
    }
    $out = & (Join-Path $scripts 'start-backend-wrapper.ps1')
    $report.Add("backend    : $out")
}

# ---------- 前端 dev（28080）----------
if ((Test-Port 28080) -and -not $Restart) {
    $report.Add('frontend   : already listening on 28080')
} else {
    $spArgs = @()
    if ($Restart) { $spArgs += '-Restart' }
    $out = & (Join-Path $scripts 'start-frontend-dev.ps1') @spArgs
    $report.Add("frontend   : $out")
}

# ---------- 采集 Worker ----------
$worker = @(Find-Process 'worker.py')
if ($worker.Count -gt 0 -and -not $Restart) {
    $report.Add("worker     : already running (PID $($worker.ProcessId -join ','))")
} else {
    $spArgs = @()
    if ($Restart) { $spArgs += '-Restart' }
    try {
        $out = & (Join-Path $scripts 'start-collection-worker.ps1') @spArgs
        $report.Add("worker     : $out")
    } catch {
        $report.Add("worker     : skipped - $($_.Exception.Message)")
    }
}

# ---------- Mock 生成器（独立工程）----------
$mockEntry = Join-Path $MockRoot 'mock_generator.py'
if (Test-Path -LiteralPath $mockEntry) {
    $mock = @(Find-Process 'mock_generator.py')
    if ($mock.Count -gt 0) {
        $report.Add("mock       : already running (PID $($mock.ProcessId -join ','))")
    } else {
        try {
            $out = & (Join-Path $MockRoot 'start.ps1')
            $report.Add("mock       : $out")
        } catch {
            $report.Add("mock       : skipped - $($_.Exception.Message)")
        }
    }
} else {
    $report.Add("mock       : skipped - $mockEntry not found")
}

# ---------- 汇总 ----------
Start-Sleep -Seconds 5
$report | ForEach-Object { Write-Output $_ }
Write-Output '--- listeners ---'
foreach ($port in 18080, 28080) {
    $conn = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($conn.Count -gt 0) {
        $pid_ = $conn[0].OwningProcess
        $proc = Get-Process -Id $pid_ -ErrorAction SilentlyContinue
        Write-Output ("  {0}: PID {1} ({2}), window={3}" -f $port, $pid_, $proc.ProcessName, $proc.MainWindowHandle)
    } else {
        Write-Output ("  {0}: NOT listening" -f $port)
    }
}
