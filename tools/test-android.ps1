param([Parameter(Mandatory=$true)][string]$Device, [string]$SdkPath='E:\Android\sdk', [switch]$LiveApi)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskAdb=Join-Path $SdkPath 'platform-tools\adb.exe'
Push-Location -LiteralPath $taskRoot
try {
    & '.\gradlew.bat' :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if($LASTEXITCODE -ne 0){throw 'Build failed.'}
    & $taskAdb -s $Device install -r 'app\build\outputs\apk\debug\app-debug.apk'
    if($LASTEXITCODE -ne 0){throw 'Cannot install debug app; use a dedicated test emulator.'}
    & $taskAdb -s $Device install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if($LASTEXITCODE -ne 0){throw 'Cannot install instrumentation APK.'}
    & $taskAdb -s $Device shell am start -n top.mossmoss.lessonisland/.MainActivity
    if($LiveApi) {
        if(-not (Test-Path -LiteralPath 'llm info.txt')){throw 'llm info.txt is required for the live API test.'}
        & $taskAdb -s $Device push 'llm info.txt' /data/local/tmp/lessonisland-api-test.txt
        & $taskAdb -s $Device shell 'run-as top.mossmoss.lessonisland cp /data/local/tmp/lessonisland-api-test.txt files/api-test-config.txt'
        & $taskAdb -s $Device shell rm /data/local/tmp/lessonisland-api-test.txt
        $taskArgs=@()
    } else {
        $taskTests='testAssetsAndOfflineAudio','testCompleteSixStagesWrongAnswerRetryAndReplayXp','testReviewReschedulingAndAnswerPrivacy','testPublicationEditingAndModeGate','testBackupValidationAndRestore','testFreshInstallAndDirectCourseCreation','testLegacySamplesArchivePreservesUserCourses'
        $taskClasses=($taskTests | ForEach-Object {'top.mossmoss.lessonisland.AppFlowTest#'+$_}) -join ','
        $taskArgs=@('-e','class',$taskClasses)
    }
    New-Item -ItemType Directory -Path 'test-results' -Force | Out-Null
    $taskResult=& $taskAdb -s $Device shell am instrument -w @taskArgs top.mossmoss.lessonisland.test/android.test.InstrumentationTestRunner
    $taskResult | Tee-Object -FilePath ('test-results\'+$Device+'-instrumentation.txt')
    if(($taskResult -join "`n") -notmatch 'OK \(\d+ tests\)'){throw 'Instrumentation tests failed.'}
    & $taskAdb -s $Device pull /sdcard/Android/data/top.mossmoss.lessonisland/files/test-screenshots ('test-results\'+$Device+'-screenshots')
} finally { Pop-Location }
