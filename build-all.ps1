# Build Forge + NeoForge + Fabric jars and copy them into dist/
# Notes:
#   * NeoForge 1.20.1 is binary compatible with Forge 1.20.1; the neoforge/ module
#     builds the same sources with the Forge toolchain, see docs/NEOFORGE-1.20.1.md.
#   * Requires JDK 17 on JAVA_HOME (or a JDK 17 toolchain).
# Usage:
#   .\build-all.ps1
#   .\build-all.ps1 -SkipForge
#   .\build-all.ps1 -SkipNeoForge
#   .\build-all.ps1 -SkipFabric
param(
    [switch]$SkipForge,
    [switch]$SkipNeoForge,
    [switch]$SkipFabric
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$targets = @()
if (-not $SkipForge)    { $targets += @{ Name = 'forge';    Pattern = 'forge\build\libs\*.jar' } }
if (-not $SkipNeoForge) { $targets += @{ Name = 'neoforge'; Pattern = 'neoforge\build\libs\*.jar' } }
if (-not $SkipFabric)   { $targets += @{ Name = 'fabric';   Pattern = 'fabric\build\libs\*.jar' } }

$failed = @()
foreach ($target in $targets) {
    $dir = Join-Path $root $target.Name
    Write-Host "==== building $($target.Name) ====" -ForegroundColor Cyan
    Push-Location $dir
    try {
        $gradlew = Join-Path $dir 'gradlew.bat'
        & $gradlew build --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle exited with $LASTEXITCODE" }
    } catch {
        Write-Host "build $($target.Name) failed: $_" -ForegroundColor Red
        $failed += $target.Name
    } finally {
        Pop-Location
    }
}

foreach ($target in $targets) {
    Get-ChildItem (Join-Path $root $target.Pattern) -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|dev' } |
        ForEach-Object {
            Copy-Item $_.FullName (Join-Path $dist $_.Name) -Force
            Write-Host "-> dist\$($_.Name)" -ForegroundColor Green
        }
}

if ($failed.Count -gt 0) {
    Write-Host "failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host "done. jars are in $dist" -ForegroundColor Green
