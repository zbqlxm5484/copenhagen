param([string]$TunnelId)
$ErrorActionPreference = 'Stop'
$previousKey = $env:CONTROL_PLANE_API_KEY
try {
    if ([string]::IsNullOrWhiteSpace($TunnelId)) {
        $TunnelId = (Read-Host 'Your OpenAI tunnel ID (tunnel_...)').Trim()
    }
    if ($TunnelId -notmatch '^tunnel_[a-zA-Z0-9]+$') { throw 'Invalid tunnel ID. Copy the ID from your own OpenAI Platform Tunnels page.' }
    if (Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Another tunnel is using port 18765. Stop that tunnel before switching accounts.'
    }
    $tunnelExe = "$PSScriptRoot\bin\tunnel-client\tunnel-client.exe"
    if (-not (Test-Path -LiteralPath $tunnelExe)) { throw 'Missing tunnel-client. Extract the whole ZIP first.' }
    if (-not (Test-Path -LiteralPath "$PSScriptRoot\.venv\Scripts\python.exe")) {
        & "$PSScriptRoot\setup-local.ps1"
    }
    & "$PSScriptRoot\start-local.ps1"
    & "$PSScriptRoot\.venv\Scripts\python.exe" "$PSScriptRoot\smoke_test.py"
    if ($LASTEXITCODE -ne 0) { throw 'Local MCP check failed.' }
    $profileDir = Join-Path "$PSScriptRoot\profiles" $TunnelId
    if (-not (Test-Path -LiteralPath "$profileDir\webcam.yaml")) {
        & $tunnelExe init --sample sample_mcp_remote_no_auth --profile webcam --profile-dir $profileDir --tunnel-id $TunnelId --mcp-server-url 'http://127.0.0.1:8765/mcp' --health-listen-addr '127.0.0.1:18765'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create tunnel profile.' }
    }
    Write-Host 'Use a runtime API key from YOUR account with Tunnels Read + Use permission.'
    Write-Host 'The key is not displayed or saved. Do not use an admin key.'
    $secureKey = Read-Host 'Runtime API key' -AsSecureString
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try { $env:CONTROL_PLANE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer) }
    if ([string]::IsNullOrWhiteSpace($env:CONTROL_PLANE_API_KEY)) { throw 'No key entered.' }
    $connectionArgs = @('--profile','webcam','--profile-dir',$profileDir,
        '--control-plane.tunnel-id',$TunnelId,'--control-plane.base-url','https://api.openai.com',
        '--control-plane.api-key','env:CONTROL_PLANE_API_KEY')
    & $tunnelExe doctor @connectionArgs --explain
    if ($LASTEXITCODE -ne 0) { throw 'Connection check failed. Check account, tunnel association, key permissions, and network.' }
    Write-Host 'Connecting. Keep this window open. Check http://127.0.0.1:18765/readyz for ready.'
    Write-Host 'After ready: ChatGPT Plugins > Create MCP app > Tunnel > YOUR tunnel > No Authentication.'
    Write-Host 'Press Ctrl+C to stop the tunnel. Run Stop Webcam.cmd to stop the local MCP too.'
    & $tunnelExe run @connectionArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tunnel stopped with an error. Review the message above.' }
} catch {
    Write-Host $_ -ForegroundColor Red
    exit 1
} finally {
    $env:CONTROL_PLANE_API_KEY = $previousKey
    if ($secureKey) { $secureKey.Dispose() }
}
