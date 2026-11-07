param([string]$SdkPath = 'E:\Android\sdk', [switch]$DebugOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $taskRoot
try {
    if (-not (Test-Path -LiteralPath $SdkPath)) { throw 'Android SDK path does not exist.' }
    ('sdk.dir=' + $SdkPath.Replace('\','/').Replace(':','\:')) | Set-Content -LiteralPath 'local.properties' -Encoding ascii
    if (-not (Test-Path -LiteralPath '.local\signing.properties')) {
        New-Item -ItemType Directory -Path '.local' -Force | Out-Null
        Copy-Item -LiteralPath 'signing.properties.example' -Destination '.local\signing.properties'
        throw 'Fill .local/signing.properties with your keystore credentials, then run this script again.'
    }
    $taskVersionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $taskRoot 'app\build.gradle')), "(?m)^\s*versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'\s*$")
    if (-not $taskVersionMatch.Success) { throw 'Cannot read the application version from app/build.gradle.' }
    $taskVersion = $taskVersionMatch.Groups[1].Value
    $taskTargets = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
    if (-not $DebugOnly) { $taskTargets += ':app:assembleRelease' }
    & '.\gradlew.bat' @taskTargets --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    New-Item -ItemType Directory -Path 'deliverables' -Force | Out-Null
    if (-not $DebugOnly) { Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination (Join-Path 'deliverables' ('LessonIsland-' + $taskVersion + '.apk')) -Force }
} finally { Pop-Location }
