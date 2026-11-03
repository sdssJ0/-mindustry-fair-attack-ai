param(
    [string]$JavaHome = '',
    [string]$GameJar = '',
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$runtimeCandidates = @($JavaHome, $env:FAIR_AI_RUNTIME_HOME, $env:JAVA_HOME, 'D:\MCLDownload\ext\jre-v64-220420\jdk17')
$runtimeHome = $null
foreach ($candidate in $runtimeCandidates) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe') -PathType Leaf)) {
        $runtimeHome = [IO.Path]::GetFullPath($candidate)
        break
    }
}
if (-not $runtimeHome) { throw 'Java 17 not found. Supply -JavaHome or set FAIR_AI_RUNTIME_HOME.' }
$java = Join-Path $runtimeHome 'bin\java.exe'
$ErrorActionPreference = 'Continue'
try { $version = (& $java '-version' 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"; $status = $LASTEXITCODE }
finally { $ErrorActionPreference = 'Stop' }
if ($status -ne 0 -or $version -notmatch 'version\s+"17\.') { throw "This launcher requires Java 17. Detected: $version" }
if (-not $GameJar) {
    foreach ($relative in @('jre\desktop.jar', '..\jre\desktop.jar', '..\..\jre\desktop.jar')) {
        $candidate = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $relative))
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $GameJar = $candidate; break }
    }
}
if (-not $GameJar -or -not (Test-Path -LiteralPath $GameJar -PathType Leaf)) { throw 'Supply -GameJar pointing to the existing Mindustry v146 desktop.jar.' }
$jarPath = [IO.Path]::GetFullPath($GameJar)
$workingPath = [IO.Path]::GetDirectoryName($jarPath)
if ([IO.Path]::GetFileName($workingPath) -eq 'jre') { $workingPath = [IO.Path]::GetDirectoryName($workingPath) }
if ($CheckOnly) { [pscustomobject]@{ java = $java; version = $version; gameJar = $jarPath; workingDirectory = $workingPath; launched = $false }; return }
$active = Get-Process -Name Mindustry -ErrorAction SilentlyContinue
if ($active) { throw 'Save and close the running game before starting the Java 17 instance.' }
$javaw = Join-Path $runtimeHome 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $javaw -PathType Leaf)) { throw 'javaw.exe is missing from the selected Java 17 installation.' }
$process = Start-Process -FilePath $javaw -ArgumentList @('-jar', ('"{0}"' -f $jarPath)) -WorkingDirectory $workingPath -WindowStyle Normal -PassThru
[pscustomobject]@{ processId = $process.Id; java = $javaw; gameJar = $jarPath; originalLauncherPreserved = $true }
