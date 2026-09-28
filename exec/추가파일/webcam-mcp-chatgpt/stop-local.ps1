$ErrorActionPreference = 'Stop'
$listeners = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
foreach ($listener in $listeners) {
    $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($serverProcess.CommandLine -like "*$PSScriptRoot\server.py*") {
        Stop-Process -Id $listener.OwningProcess
        Write-Host 'Stopped this PoC server.'
    } else {
        throw 'Port belongs to another process; nothing stopped.'
    }
}
