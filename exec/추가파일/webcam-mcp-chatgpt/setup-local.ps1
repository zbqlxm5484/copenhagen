$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath "$PSScriptRoot\.venv\Scripts\python.exe")) {
    & py -3.11 -m venv "$PSScriptRoot\.venv"
    if ($LASTEXITCODE -ne 0) { throw 'Install Python 3.11 x64 with Python Launcher first.' }
}
& "$PSScriptRoot\.venv\Scripts\python.exe" -m pip install -r "$PSScriptRoot\requirements.txt"
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
