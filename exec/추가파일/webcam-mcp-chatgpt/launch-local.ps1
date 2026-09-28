$ErrorActionPreference = 'Stop'
try {
    if (-not (Test-Path -LiteralPath "$PSScriptRoot\.venv\Scripts\python.exe")) {
        & "$PSScriptRoot\setup-local.ps1"
    }
    & "$PSScriptRoot\start-local.ps1"
    & "$PSScriptRoot\.venv\Scripts\python.exe" "$PSScriptRoot\smoke_test.py"
    if ($LASTEXITCODE -ne 0) { throw 'MCP connection failed. Check logs/server.err.log.' }
    & "$PSScriptRoot\.venv\Scripts\python.exe" "$PSScriptRoot\desktop.py"
} catch {
    Write-Host $_ -ForegroundColor Red
    Read-Host 'Press Enter to close'
} finally {
    & "$PSScriptRoot\stop-local.ps1"
}
