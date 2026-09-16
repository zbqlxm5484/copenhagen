param(
    [Parameter(Mandatory = $true)][ValidatePattern('^tunnel_[A-Za-z0-9]{1,64}$')][string]$TunnelId,
    [Parameter(Mandatory = $true)][string]$ClientPath,
    [ValidateSet(1, 2, 3)][int]$Stage = 1,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ClientPath -PathType Leaf)) { throw 'Install the official OpenAI tunnel-client first.' }
$port = 8090 + $Stage
$expectedRuntime = switch ($Stage) { 3 { 'cart-mock' } 2 { 'catalog-mock' } default { 'connection-only' } }
$runtimeRoot = Join-Path (Split-Path -Parent $PSScriptRoot) $(if ($Stage -gt 1) { "build\qa$Stage" } else { 'build\qa' })
$profiles = Join-Path $runtimeRoot 'tunnel-profiles'
$profileName = "copenhagen-qa$Stage-" + $TunnelId
$profile = Join-Path $profiles ($profileName + '.yaml')
$probe = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/qa/check" -TimeoutSec 10
if ($probe.Headers['X-Copenhagen-Qa'] -ne $expectedRuntime) { throw "Port $port is not the QA$Stage runtime." }
$data = $probe.Content | ConvertFrom-Json
if ($data.isError -or $data.structuredContent.service -ne 'copenhagen-mcp') { throw 'QA MCP round trip failed.' }
if (-not (Test-Path -LiteralPath $profile)) {
    & $ClientPath init --sample sample_mcp_remote_no_auth --profile $profileName --profile-dir $profiles `
        --tunnel-id $TunnelId --mcp-server-url "http://127.0.0.1:$port/mcp" --health-listen-addr 127.0.0.1:0
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create QA tunnel profile.' }
}
if ($Check) { Write-Output "QA$Stage runtime and tunnel profile are ready."; exit 0 }
Write-Host "Copenhagen QA$Stage | $expectedRuntime | localhost:$port"
Write-Host 'Enter the runtime key with Tunnels Read + Use. It will not be saved.'
$secureKey = Read-Host 'Runtime API key' -AsSecureString
if ($secureKey.Length -eq 0) { $secureKey.Dispose(); throw 'No runtime key entered.' }
$pointer = [IntPtr]::Zero
$previousKey = [Environment]::GetEnvironmentVariable('CONTROL_PLANE_API_KEY', 'Process')
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $env:CONTROL_PLANE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $pointer = [IntPtr]::Zero
    & $ClientPath doctor --profile $profileName --profile-dir $profiles
    if ($LASTEXITCODE -ne 0) { throw 'Tunnel verification failed.' }
    Write-Host 'Keep this console open during phone QA. Press Ctrl+C to stop only this tunnel.'
    & $ClientPath run --profile $profileName --profile-dir $profiles `
        --health.url-file (Join-Path $runtimeRoot 'tunnel-health.url') --pid.file (Join-Path $runtimeRoot 'tunnel.pid')
    $tunnelExit = $LASTEXITCODE
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $secureKey.Dispose()
    [Environment]::SetEnvironmentVariable('CONTROL_PLANE_API_KEY', $previousKey, 'Process')
    $previousKey = $null
}
exit $tunnelExit
