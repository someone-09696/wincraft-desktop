# WinCraft Desktop Windows x64 build helper. Run from PowerShell, not from inside Minecraft.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -Install
# -MinecraftDir supports nonstandard/launcher-specific game directories.
[CmdletBinding()]
param(
    [switch] $Install,
    [string] $MinecraftDir = (Join-Path $env:APPDATA '.minecraft')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
function Assert-Exit([string] $step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit code $LASTEXITCODE). Read the errors immediately above." }
}
if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
    throw 'WinCraft native build requires Windows 10/11 x64.'
}
if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess) {
    throw 'Run 64-bit PowerShell on Windows x64.'
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 25 installation first.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "Cannot find $java. JAVA_HOME must be a JDK 25 directory." }
$version = ((& $java --version) | Select-Object -First 1).ToString()
Assert-Exit 'Java version check'
if ($version -notmatch '(?i)(?:^|\s)(?:openjdk|java)\s+25(?:\.|\s|$)') { throw "Java 25 is required; found $version" }
if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw 'Install CMake and Visual Studio 2022 Desktop development with C++ + Windows SDK.'
}
$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { throw "Missing Gradle wrapper: $gradlew" }
$buildDir = Join-Path $root 'native-build'
Write-Host '[1/3] Building Windows x64 WGC/OpenGL native DLL...'
$nativeSrc = Join-Path $root 'src\native'
$built = $false
try {
    & cmake -S $nativeSrc -B $buildDir -G 'Visual Studio 17 2022' -A x64
    if ($LASTEXITCODE -eq 0) {
        & cmake --build $buildDir --config Release
        if ($LASTEXITCODE -eq 0) { $built = $true }
    }
} catch { }

if (-not $built) {
    Write-Host 'Visual Studio generator not available, building via MSVC BuildTools / Ninja...'
    $vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
    if (Test-Path -LiteralPath $vcvars) {
        Remove-Item -Recurse -Force $buildDir -ErrorAction SilentlyContinue
        cmd /v:on /c "call `"$vcvars`" && set `"PATH=C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64;C:\Program Files\CMake\bin;C:\Users\el\AppData\Local\Microsoft\WinGet\Links;!PATH!`" && cmake -S `"$nativeSrc`" -B `"$buildDir`" -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build `"$buildDir`""
        Assert-Exit 'Ninja/MSVC native build'
    } else {
        throw 'No Visual Studio 2022 or MSVC vcvars64.bat found.'
    }
}

$dll = Join-Path $buildDir 'Release\wincraft_native.dll'
if (-not (Test-Path -LiteralPath $dll)) {
    $dll = Join-Path $buildDir 'wincraft_native.dll'
}
if (-not (Test-Path -LiteralPath $dll)) { throw "Native build did not produce $dll" }
$destinationDir = Join-Path $root 'src\main\resources\natives\windows-x86_64'
New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
Copy-Item -LiteralPath $dll -Destination (Join-Path $destinationDir 'wincraft_native.dll') -Force
Write-Host '[2/3] Building mod with the native DLL embedded...'
Push-Location $root
try {
    & $gradlew --no-daemon --max-workers=2 build
    Assert-Exit 'Gradle build'
} finally { Pop-Location }
$jar = Get-ChildItem -LiteralPath (Join-Path $root 'build\libs') -Filter 'wincraft-desktop-*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw 'Gradle reported success, but the mod JAR was not found.' }
Write-Host "Built mod: $($jar.FullName)"
if (-not $Install) {
    Write-Host 'Build only. Re-run with -Install to copy the JAR to your Minecraft mods folder.'
    return
}
Write-Host '[3/3] Checking Fabric installation and copying to mods...'
$mods = Join-Path $MinecraftDir 'mods'
if (-not (Test-Path -LiteralPath $MinecraftDir)) {
    throw "Minecraft directory does not exist: $MinecraftDir. Start Minecraft/Fabric once or pass -MinecraftDir."
}
New-Item -ItemType Directory -Force -Path $mods | Out-Null
$api = @(Get-ChildItem -LiteralPath $mods -Filter 'fabric-api-*+26.2.jar' -ErrorAction SilentlyContinue)
if ($api.Count -eq 0) {
    throw "Fabric API for Minecraft 26.2 is missing from $mods. Install it before copying WinCraft."
}
$older = @(Get-ChildItem -LiteralPath $mods -Filter 'wincraft-desktop-*.jar' |
    Where-Object { $_.Name -ne $jar.Name })
if ($older.Count -gt 0) {
    throw ('Remove old WinCraft JAR(s) from mods first: ' + (($older | ForEach-Object Name) -join ', '))
}
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $mods $jar.Name) -Force
Write-Host "Installed $($jar.Name) in $mods"
Write-Host 'Start the Fabric 26.2 profile with the OpenGL graphics backend. Press B in a world.'
Write-Warning 'Installation is not a proof that native capture, app input, clipboard or OLE drag/drop work on your Windows system.'
