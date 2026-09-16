param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = [IO.Path]::GetFullPath((Join-Path $moduleRoot '..\..'))
$runtimeRoot = Join-Path $moduleRoot 'build\qa'
if (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8091 is already in use. Keep the existing service or run this module stop-qa.ps1 first.'
}
if (-not $JavaHome) {
    $jdkRoot = Join-Path $env:USERPROFILE '.gradle\jdks'
    $jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '-21-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
        Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a Java 21 JDK, or pass -JavaHome.'
}
$env:JAVA_HOME = $JavaHome
Push-Location $backendRoot
try {
    & .\gradlew.bat :apps:mcp-server:writeQaClasspath --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'QA build failed.' }
} finally { Pop-Location }
$classPath = (Get-Content -LiteralPath (Join-Path $runtimeRoot 'classpath.txt') -Raw).Trim().Replace('\', '/')
$argFile = Join-Path $runtimeRoot 'java.args'
$arguments = '-Dfile.encoding=UTF-8' + "`n-cp`n" + '"' + $classPath + '"' + "`ncom.copenhagen.mcp.qa.LocalMcpQa`n"
[IO.File]::WriteAllText($argFile, $arguments, [Text.UTF8Encoding]::new($false))
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList ('@"' + $argFile + '"') `
    -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtimeRoot 'server.out.log') `
    -RedirectStandardError (Join-Path $runtimeRoot 'server.err.log')
@{ id = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'process.json') -Encoding UTF8
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($process.HasExited) { throw 'QA process exited. See build/qa/server logs.' }
        try {
            $result = Invoke-RestMethod -Uri 'http://127.0.0.1:8091/qa/check' -TimeoutSec 5
            if (-not $result.isError -and $result.structuredContent.service -eq 'copenhagen-mcp') {
                $ready = $true
                Write-Output ('QA1 READY | version=' + $result.structuredContent.uiVersion)
                break
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'QA startup verification failed. See build/qa/server logs.' }
    Write-Output 'Preview: http://127.0.0.1:8091/qa | MCP: http://127.0.0.1:8091/mcp'
} catch {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
    throw
}
