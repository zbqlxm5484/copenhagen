$ErrorActionPreference = 'Stop'
$listener = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
    if ($existing.CommandLine -notlike "*$PSScriptRoot\server.py*") {
        throw 'Port 8765 is occupied by another application.'
    }
    Write-Host 'Webcam MCP is already running at http://127.0.0.1:8765/mcp'
    return
}
New-Item -ItemType Directory -Path "$PSScriptRoot\logs" -Force | Out-Null
$pythonPath = "$PSScriptRoot\.venv\Scripts\python.exe"
$scriptPath = '"' + "$PSScriptRoot\server.py" + '"'
$serverProcess = Start-Process -FilePath $pythonPath -ArgumentList $scriptPath -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput "$PSScriptRoot\logs\server.out.log" -RedirectStandardError "$PSScriptRoot\logs\server.err.log" -PassThru
$serverProcess.Id | Set-Content -LiteralPath "$PSScriptRoot\logs\server.pid"
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $ready = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
    if ($ready) { break }
    Start-Sleep -Milliseconds 250
}
if (-not $ready) { throw 'Server did not start. See logs/server.err.log.' }
Write-Host 'Started webcam MCP at http://127.0.0.1:8765/mcp (loopback only).'
