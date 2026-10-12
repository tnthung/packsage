[CmdletBinding()]
param(
    [string]$SourceRoot,
    [string]$OutputRoot,
    [ValidateSet('full', 'no-jei', 'no-ftb-quests', 'no-patchouli')]
    [string]$Variant = 'full'
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path $PSScriptRoot -Parent
$VariantSuffix = if ($Variant -eq 'full') { '' } else { "-$Variant" }
$SourceRoot = if ($SourceRoot) { $SourceRoot } else { Join-Path $RepoRoot '.superpowers/scratch-artifacts' }
$OutputRoot = if ($OutputRoot) { $OutputRoot } else { Join-Path $RepoRoot "instances/packsage-minimal$VariantSuffix" }
$FixtureRoot = Join-Path $RepoRoot 'fixtures/minimal'
$Manifest = Get-Content (Join-Path $FixtureRoot 'manifest.json') -Raw | ConvertFrom-Json
$ExcludedArtifacts = switch ($Variant) {
    'no-jei' { @('jei') }
    'no-ftb-quests' { @('ftbquests', 'ftblibrary', 'ftbteams') }
    'no-patchouli' { @('patchouli', 'naturesaura') }
    default { @() }
}
$Artifacts = @($Manifest.artifacts | Where-Object { $ExcludedArtifacts -notcontains $_.id })
foreach ($ExcludedArtifact in $Manifest.artifacts | Where-Object { $ExcludedArtifacts -contains $_.id }) {
    if (Test-Path -LiteralPath (Join-Path $OutputRoot "mods/$($ExcludedArtifact.file)")) {
        throw "Excluded mod is already present in $OutputRoot; use a fresh variant instance."
    }
}
$ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot '.tools'))
$ImportRoot = [System.IO.Path]::GetFullPath((Join-Path $ToolsRoot "fixture-import$VariantSuffix"))
$ToolsPrefix = $ToolsRoot.TrimEnd('\') + '\'
if (-not $ImportRoot.StartsWith($ToolsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Import staging path escaped the repository tools directory: $ImportRoot"
}
if (Test-Path -LiteralPath $ToolsRoot) {
    $ToolsItem = Get-Item -LiteralPath $ToolsRoot -Force
    if (($ToolsItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing reparse-point tools directory: $ToolsRoot"
    }
}
if (Test-Path -LiteralPath $ImportRoot) {
    $ImportItem = Get-Item -LiteralPath $ImportRoot -Force
    if (($ImportItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing reparse-point import staging directory: $ImportRoot"
    }
    if (-not $ImportItem.PSIsContainer) {
        throw "Import staging path is not a directory: $ImportRoot"
    }
}
$ImportZip = Join-Path $RepoRoot "dist/packsage-minimal-fixture$VariantSuffix.zip"

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutputRoot 'mods') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutputRoot 'datapacks') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutputRoot 'config/ftbquests/quests/chapters') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutputRoot 'resourcepacks') | Out-Null

foreach ($Artifact in $Artifacts) {
    $RelativeSource = $Artifact.source -replace '^\.superpowers/scratch-artifacts[/\\]', ''
    $Source = Join-Path $SourceRoot ($RelativeSource -replace '/', '\')
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $Source -Parent) | Out-Null
        & curl.exe -fL --retry 2 --output $Source $Artifact.url
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed for $($Artifact.file) with exit code $LASTEXITCODE"
        }
    }
    $ActualHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($ActualHash -ne $Artifact.sha256.ToUpperInvariant()) {
        throw "SHA-256 mismatch for $($Artifact.file): expected $($Artifact.sha256), got $ActualHash"
    }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $OutputRoot "mods/$($Artifact.file)") -Force
}

$DatapackDestination = Join-Path $OutputRoot 'datapacks/packsage_fixture'
$DatapackSource = Join-Path $FixtureRoot 'datapack/packsage_fixture'
Get-ChildItem -LiteralPath $DatapackSource -File -Recurse | ForEach-Object {
    $RelativePath = $_.FullName.Substring($DatapackSource.Length).TrimStart('\')
    $Destination = Join-Path $DatapackDestination $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path $Destination -Parent) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $Destination -Force
}

$QuestSource = Join-Path $FixtureRoot 'quests/packsage_fixture.snbt'
Copy-Item -LiteralPath $QuestSource -Destination (Join-Path $OutputRoot 'config/ftbquests/quests/chapters/packsage_fixture.snbt') -Force
$QuestLanguageSource = Join-Path $FixtureRoot 'quests/lang/en_us.snbt'
New-Item -ItemType Directory -Force -Path (Join-Path $OutputRoot 'config/ftbquests/quests/lang') | Out-Null
Copy-Item -LiteralPath $QuestLanguageSource -Destination (Join-Path $OutputRoot 'config/ftbquests/quests/lang/en_us.snbt') -Force

$ResourceSource = Join-Path $FixtureRoot 'resources/packsage_resourcepack'
$ResourceDestination = Join-Path $OutputRoot 'resourcepacks/packsage_resourcepack'
Get-ChildItem -LiteralPath $ResourceSource -File -Recurse | ForEach-Object {
    $RelativePath = $_.FullName.Substring($ResourceSource.Length).TrimStart('\')
    $Destination = Join-Path $ResourceDestination $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path $Destination -Parent) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $Destination -Force
}
$ResourceZip = Join-Path $OutputRoot 'resourcepacks/packsage_resourcepack.zip'
if (Test-Path -LiteralPath $ResourceZip) {
    Remove-Item -LiteralPath $ResourceZip -Force
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ResourceArchive = [System.IO.Compression.ZipFile]::Open($ResourceZip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $ResourceSource -File -Recurse | ForEach-Object {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($ResourceArchive, $_.FullName,
                $_.FullName.Substring($ResourceSource.Length).TrimStart('\').Replace('\', '/'),
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $ResourceArchive.Dispose()
}

if (Test-Path -LiteralPath $ImportRoot) {
    Remove-Item -LiteralPath $ImportRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $ImportRoot 'overrides') | Out-Null
$CurseForgeManifest = [ordered]@{
    manifestType = 'minecraftModpack'
    manifestVersion = 1
    name = "PackSage Minimal Fixture$VariantSuffix"
    version = '1.0.0'
    author = 'PackSage'
    files = @($Artifacts | ForEach-Object {
        if ($_.curseforge -notmatch '^curse\.maven:.*-(\d+):(\d+)$') {
            throw "Invalid pinned CurseForge coordinate for $($_.id)"
        }
        [ordered]@{ projectID = [int]$Matches[1]; fileID = [int]$Matches[2]; required = $true }
    })
    overrides = 'overrides'
    minecraft = [ordered]@{
        version = '1.21.1'
        modLoaders = @([ordered]@{ id = 'neoforge-21.1.202'; primary = $true })
    }
}
New-Item -ItemType Directory -Force -Path $ImportRoot | Out-Null
$ManifestJson = $CurseForgeManifest | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $ImportRoot 'manifest.json'), $ManifestJson, [System.Text.UTF8Encoding]::new($false))
$ImportData = @(
    @{ Source = $DatapackSource; Relative = 'overrides/datapacks/packsage_fixture' },
    @{ Source = $ResourceSource; Relative = 'overrides/resourcepacks/packsage_resourcepack' },
    @{ Source = $QuestSource; Relative = 'overrides/config/ftbquests/quests/chapters/packsage_fixture.snbt' },
    @{ Source = $QuestLanguageSource; Relative = 'overrides/config/ftbquests/quests/lang/en_us.snbt' },
    @{ Source = $ResourceZip; Relative = 'overrides/resourcepacks/packsage_resourcepack.zip' }
)
foreach ($Entry in $ImportData) {
    if (Test-Path -LiteralPath $Entry.Source -PathType Container) {
        Get-ChildItem -LiteralPath $Entry.Source -File -Recurse | ForEach-Object {
            $RelativePath = $_.FullName.Substring($Entry.Source.Length).TrimStart('\')
            $Destination = Join-Path (Join-Path $ImportRoot $Entry.Relative) $RelativePath
            New-Item -ItemType Directory -Force -Path (Split-Path $Destination -Parent) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $Destination -Force
        }
    } else {
        $Destination = Join-Path $ImportRoot $Entry.Relative
        New-Item -ItemType Directory -Force -Path (Split-Path $Destination -Parent) | Out-Null
        Copy-Item -LiteralPath $Entry.Source -Destination $Destination -Force
    }
}
New-Item -ItemType Directory -Force -Path (Split-Path $ImportZip -Parent) | Out-Null
if (Test-Path -LiteralPath $ImportZip) {
    Remove-Item -LiteralPath $ImportZip -Force
}
$ImportArchive = [System.IO.Compression.ZipFile]::Open($ImportZip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $ImportRoot -File -Recurse | ForEach-Object {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($ImportArchive, $_.FullName,
                $_.FullName.Substring($ImportRoot.Length).TrimStart('\').Replace('\', '/'),
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $ImportArchive.Dispose()
}

$DataFiles = @(
    (Join-Path $DatapackDestination 'pack.mcmeta'),
    (Join-Path $DatapackDestination 'data/minecraft/recipe/torch.json'),
    (Join-Path $DatapackDestination 'data/packsage/tags/item/fixture_inputs.json'),
    (Join-Path $DatapackDestination 'data/packsage/recipe/fixture_lantern.json'),
    (Join-Path $OutputRoot 'config/ftbquests/quests/chapters/packsage_fixture.snbt'),
        (Join-Path $OutputRoot 'config/ftbquests/quests/lang/en_us.snbt'),
    (Join-Path $ResourceDestination 'pack.mcmeta'),
    (Join-Path $ResourceDestination 'assets/naturesaura/patchouli_books/book/en_us/entries/intro/aura.json')
)
foreach ($DataFile in $DataFiles) {
    if (-not (Test-Path -LiteralPath $DataFile -PathType Leaf)) {
        throw "Fixture data was not prepared: $DataFile"
    }
}

Write-Output "Prepared PackSage minimal fixture at $OutputRoot"
Write-Output "Validated and copied $($Artifacts.Count) pinned jars for variant $Variant"
Write-Output "Datapack, FTB Quests chapter and Patchouli resource override are ready"
Write-Output "CurseForge import archive: $ImportZip"
