param(
    [string]$AdminUuid,
    [ValidateRange(1, 65535)][int]$Port = 8787
)

$ErrorActionPreference = 'Stop'
if (-not $AdminUuid) {
    $configPath = Join-Path $PSScriptRoot '.env'
    if (Test-Path -LiteralPath $configPath) {
        $adminLine = Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^\s*TONGCRAFT_ADMIN_UUID\s*=' } | Select-Object -First 1
        if ($adminLine) { $AdminUuid = ($adminLine -replace '^\s*TONGCRAFT_ADMIN_UUID\s*=\s*', '').Trim().Trim('"', "'") }
    }
}
if ($AdminUuid -notmatch '^(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$') {
    throw 'Provide a genuine Minecraft UUID using -AdminUuid or TONGCRAFT_ADMIN_UUID in .env.'
}
$nodeVersion = & node --version
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.(\d+)\.(\d+)' -or [version]($nodeVersion.TrimStart('v')) -lt [version]'24.13.0') {
    throw 'Node.js 24.13.0 or newer is required.'
}
$previousEnvironment = @{}
foreach ($name in @('TONGCRAFT_ADMIN_UUID', 'HOST', 'PORT', 'TONGCRAFT_DATA_DIR')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:TONGCRAFT_ADMIN_UUID = $AdminUuid
    $env:HOST = '127.0.0.1'
    $env:PORT = "$Port"
    $env:TONGCRAFT_DATA_DIR = Join-Path $PSScriptRoot 'service/data'
    Push-Location (Join-Path $PSScriptRoot 'service')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules/ws/package.json')) {
            & npm ci
            if ($LASTEXITCODE -ne 0) { throw "Dependency installation failed (exit $LASTEXITCODE)." }
        }
        Write-Host "In-game sync address: http://127.0.0.1:$Port ; game address: mc.tongcraft.cn"
        & node src/main.js
        if ($LASTEXITCODE -ne 0) { throw "Sync service exited with code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
