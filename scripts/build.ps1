param([switch]$Bootstrap)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
Push-Location $root
try {
    $jdk = Get-ChildItem (Join-Path $root '.tools/jdk') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $jdk) { throw 'A portable Java 21 installation was not found under .tools/jdk.' }
    $java = Join-Path $jdk.FullName 'bin/java.exe'
    $javaVersion = (& $java --version | Out-String)
    if ($javaVersion -notmatch '(?m)^(?:openjdk|java) 21(?:\.|$)') { throw "Expected Java 21 under .tools/jdk, got: $javaVersion" }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = (Join-Path $jdk.FullName 'bin') + [IO.Path]::PathSeparator + $oldPath
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Bootstrap) {
            & .\gradlew.bat -PpacksageBootstrapOnly=true test build
        } else {
            & .\gradlew.bat test build
        }
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($gradleExit -ne 0) { throw "Gradle failed with exit code $gradleExit." }
    $properties = Get-Content (Join-Path $root 'gradle.properties')
    $modVersion = ($properties | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', ''
    $neoVersion = ($properties | Where-Object { $_ -match '^neo_version=' }) -replace '^neo_version=', ''
    $minecraftVersion = ($properties | Where-Object { $_ -match '^minecraft_version=' }) -replace '^minecraft_version=', ''
    $jar = Get-Item (Join-Path $root "build/libs/packsage-$modVersion.jar") -ErrorAction Stop
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    $distJar = if ($Bootstrap) { 'packsage-bootstrap.jar' } else { 'packsage.jar' }
    Copy-Item $jar.FullName (Join-Path $dist $distJar) -Force
    $hash = Get-FileHash (Join-Path $dist $distJar) -Algorithm SHA256
    [ordered]@{
        mod = 'packsage'
        version = $modVersion
        minecraft = $minecraftVersion
        neoforge = $neoVersion
        java = $javaVersion.Trim()
        gradle = '8.11.1'
        modDevGradle = '2.0.147'
        jar = $distJar
        scope = if ($Bootstrap) { 'bootstrap' } else { 'full' }
        sha256 = $hash.Hash
    } | ConvertTo-Json | Set-Content (Join-Path $dist 'build-metadata.json')
    $mods = 'C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods'
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    Copy-Item -LiteralPath (Join-Path $dist $distJar) -Destination (Join-Path $mods 'packsage.jar') -Force
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
