param([switch]$Restart)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = Join-Path $projectRoot 'deploy/config/.env.worker.local'
if (!(Test-Path -LiteralPath $envFile)) { throw 'Create deploy/config/.env.worker.local from .env.worker.example first.' }
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
if (!$env:COLLECTION_WORKER_TOKEN -or $env:COLLECTION_WORKER_TOKEN.Length -lt 32 -or !$env:COLLECTION_WORKER_FILES_ROOT) {
    throw 'Worker token (32+ characters) and actual files root are required.'
}
$entry = Join-Path $projectRoot 'backend/collection-python/worker.py'
$running = @(Get-CimInstance Win32_Process -Filter "name='python.exe'" | Where-Object { $_.CommandLine -like "*$entry*" })
if ($running.Count -gt 0 -and !$Restart) { throw 'Project collection Worker is already running; pass -Restart.' }
foreach ($process in $running) { Stop-Process -Id $process.ProcessId }
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$pythonExe = if ($env:COLLECTION_WORKER_PYTHON) { $env:COLLECTION_WORKER_PYTHON } else { (Get-Command python.exe).Source }
$workerProcess = Start-Process -FilePath $pythonExe -WindowStyle Hidden -PassThru -WorkingDirectory $projectRoot `
    -ArgumentList @('-X', 'utf8', ('"' + $entry + '"'), '--files-root', ('"' + $env:COLLECTION_WORKER_FILES_ROOT + '"')) `
    -RedirectStandardOutput (Join-Path $logDir 'collection-worker.out.log') `
    -RedirectStandardError (Join-Path $logDir 'collection-worker.err.log')
Write-Output "Collection Worker PID: $($workerProcess.Id)"
