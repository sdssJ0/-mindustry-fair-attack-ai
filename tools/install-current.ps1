$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourcePath = Join-Path $projectPath 'build\fair-attack-ai.jar'
$metadata = Get-Content -LiteralPath (Join-Path $projectPath 'mod.hjson') -Raw -Encoding UTF8
if ($metadata -notmatch '(?m)^version:\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$') { throw 'Invalid mod version.' }
$version = $Matches[1]
$proofPath = Join-Path $projectPath ("build\reports\v$version-industrial\verification.json")
$proof = Get-Content -LiteralPath $proofPath -Raw -Encoding UTF8 | ConvertFrom-Json
$expectedHash = $proof.jarSha256
if ($proof.version -ne $version) { throw 'The verification report does not describe the current release.' }
if (Get-Process -Name Mindustry -ErrorAction SilentlyContinue) { throw 'Save and close Mindustry before updating its mod and test maps.' }
$runningJavaGame = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '-jar\s+(?:"[^"]*desktop\.jar"|[^\s"]*desktop\.jar)(?:\s|$)'
}
if ($runningJavaGame) { throw 'Save and close the Java-launched Mindustry instance before updating its mod and test maps.' }
if ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'The mod does not match the verified build. Run verification again.'
}
$modsPath = [IO.Path]::GetFullPath((Join-Path $env:APPDATA 'Mindustry\mods'))
$mapsPath = [IO.Path]::GetFullPath((Join-Path $env:APPDATA 'Mindustry\maps'))
$mapNames = @('fair-ai-0.msav', 'fair-ai-1.msav', 'fair-ai-2.msav', 'fair-ai-industry.msav')
foreach ($name in $mapNames) {
    $mapSource = Join-Path $projectPath ('build\test-maps\' + $name)
    $expectedMapHash = $proof.mapsSha256.PSObject.Properties[$name].Value
    if (-not $expectedMapHash -or (Get-FileHash -LiteralPath $mapSource).Hash -ne $expectedMapHash) { throw "Map does not match verification: $name" }
}
if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) { throw 'Mindustry mod directory is missing.' }
$destinationPath = Join-Path $modsPath 'fair-attack-ai.jar'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupPath = [IO.Path]::GetFullPath((Join-Path $projectPath "build\installed-backups\$stamp"))
if (-not $backupPath.StartsWith($projectPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup path is outside the project.'
}
$oldFiles = @()
foreach ($file in Get-ChildItem -LiteralPath $modsPath -File) {
    if ($file.Extension -notin @('.zip', '.jar')) { continue }
    $archive = $null
    $memoryZip = $null
    try {
        # Read metadata from memory so archive readers cannot retain a lock on the old mod.
        $memoryZip = [IO.MemoryStream]::new([IO.File]::ReadAllBytes($file.FullName), $false)
        $archive = [IO.Compression.ZipArchive]::new($memoryZip, [IO.Compression.ZipArchiveMode]::Read, $false)
        $entry = $archive.GetEntry('mod.hjson')
        if ($null -eq $entry) { continue }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { $meta = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($meta -match '(?m)^name:\s*fair-attack-ai\s*$') { $oldFiles += $file }
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
        if ($null -ne $memoryZip) { $memoryZip.Dispose() }
    }
}
if ((Test-Path -LiteralPath $destinationPath) -and -not ($oldFiles.FullName -contains $destinationPath)) {
    throw 'Destination belongs to an unrecognized file; refusing to overwrite it.'
}
New-Item -ItemType Directory -Path $backupPath | Out-Null
foreach ($file in $oldFiles) {
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($file.FullName)) -ne $modsPath) { throw 'Unexpected old-mod path.' }
    $backupFile = Join-Path $backupPath $file.Name
    Copy-Item -LiteralPath $file.FullName -Destination $backupFile
    if ((Get-FileHash -LiteralPath $backupFile).Hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash) { throw 'Backup checksum mismatch.' }
}
$installedPaths = @()
if ($oldFiles.Count -gt 0) {
    # Updating existing matching archives also works when Windows denies file renaming.
    # Every old archive has already been copied and checksum-verified in the backup directory.
    foreach ($file in $oldFiles) {
        Copy-Item -LiteralPath $sourcePath -Destination $file.FullName -Force
        if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne $expectedHash) { throw 'Installed mod checksum mismatch.' }
        $installedPaths += $file.FullName
    }
} else {
    Copy-Item -LiteralPath $sourcePath -Destination $destinationPath
    if ((Get-FileHash -LiteralPath $destinationPath).Hash -ne $expectedHash) { throw 'Installed mod checksum mismatch.' }
    $installedPaths += $destinationPath
}
$installedMaps = @()
if (-not (Test-Path -LiteralPath $mapsPath -PathType Container)) { New-Item -ItemType Directory -Path $mapsPath | Out-Null }
foreach ($name in $mapNames) {
    $mapDestination = [IO.Path]::GetFullPath((Join-Path $mapsPath $name))
    if ([IO.Path]::GetDirectoryName($mapDestination) -ne $mapsPath) { throw 'Unexpected map destination.' }
    if (Test-Path -LiteralPath $mapDestination) {
        $mapBackup = Join-Path $backupPath ('map-' + $name)
        Copy-Item -LiteralPath $mapDestination -Destination $mapBackup
        if ((Get-FileHash -LiteralPath $mapBackup).Hash -ne (Get-FileHash -LiteralPath $mapDestination).Hash) { throw 'Map backup checksum mismatch.' }
    }
    Copy-Item -LiteralPath (Join-Path $projectPath ('build\test-maps\' + $name)) -Destination $mapDestination -Force
    if ((Get-FileHash -LiteralPath $mapDestination).Hash -ne $proof.mapsSha256.PSObject.Properties[$name].Value) { throw 'Installed map checksum mismatch.' }
    $installedMaps += $mapDestination
}
[pscustomobject]@{
    installed = $installedPaths
    version = $proof.version
    sha256 = $expectedHash
    backup = $backupPath
    matchingArchivesUpdated = $installedPaths.Count
    restartRequired = $true
    maps = $installedMaps
} | ConvertTo-Json
