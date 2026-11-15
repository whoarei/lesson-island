param([Parameter(Mandatory=$true)][string]$Device, [string]$SdkPath='E:\Android\sdk', [switch]$LiveApi, [string]$BuiltApksPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskAdb=Join-Path $SdkPath 'platform-tools\adb.exe'
Push-Location -LiteralPath $taskRoot
try {
    if ($BuiltApksPath) {
        $taskBuiltPath = (Resolve-Path -LiteralPath $BuiltApksPath).Path
        & py -B tools/test_apks.py verify $taskBuiltPath
        if ($LASTEXITCODE -ne 0) { throw 'Signed test APK verification failed.' }
        $taskAppApk = Join-Path $taskBuiltPath 'LessonIsland-debug.apk'
        $taskTestApk = Join-Path $taskBuiltPath 'LessonIsland-androidTest.apk'
    } else {
        & '.\gradlew.bat' :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        if($LASTEXITCODE -ne 0){throw 'Build failed.'}
        $taskAppApk = 'app\build\outputs\apk\debug\app-debug.apk'
        $taskTestApk = 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    }
    & $taskAdb -s $Device install -r $taskAppApk
    if($LASTEXITCODE -ne 0){throw 'Cannot install debug app; use a dedicated test emulator.'}
    & $taskAdb -s $Device install -r $taskTestApk
    if($LASTEXITCODE -ne 0){throw 'Cannot install instrumentation APK.'}
    & $taskAdb -s $Device shell am start -n top.mossmoss.lessonisland/.MainActivity
    $taskTests='testAssetsAndOfflineAudio','testCompleteSixStagesWrongAnswerRetryAndReplayXp','testReviewReschedulingAndAnswerPrivacy','testPublicationEditingAndModeGate','testBackupValidationAndRestore','testFreshInstallAndDirectCourseCreation','testLegacySamplesArchivePreservesUserCourses','testApiKeyVisibilityDoesNotRevealSavedValue','testAdventureRouteAndPinnedActionsAtNarrowWidth','testMaterialTapPlaybackAndLongPressEditing','testContinuousAudioAdvancementCancellationAndFailure','testContinuousPassageHitTargetsAndWrappedEditing','testMaterialHeaderTitleAndIconActions','testVoiceSettingsAndUnsavedForm','testItemAudioReplacementSaveCancelAndLearning','testCustomAudioBackupValidationAndSampleMigration'
    $taskTests += 'testPublishedEditingCreatesTimestampedDraftAutomatically','testMultipleDraftPublicationDeletionAndBackup','testDraftPickerSelectionAndDeletion'
    if($LiveApi) {
        if(-not (Test-Path -LiteralPath 'llm info.txt')){throw 'llm info.txt is required for the live API test.'}
        & $taskAdb -s $Device push 'llm info.txt' /data/local/tmp/lessonisland-api-test.txt
        & $taskAdb -s $Device shell 'run-as top.mossmoss.lessonisland cp /data/local/tmp/lessonisland-api-test.txt files/api-test-config.txt'
        & $taskAdb -s $Device shell rm /data/local/tmp/lessonisland-api-test.txt
        $taskTests += 'testZLiveImageToPublishedCourse'
    }
    $taskClasses=((($taskTests | ForEach-Object {'top.mossmoss.lessonisland.AppFlowTest#'+$_}) + @(
        'top.mossmoss.lessonisland.SessionFlowTest',
        'top.mossmoss.lessonisland.StudyTimeLedgerTest',
        'top.mossmoss.lessonisland.UpdateFlowTest',
        'top.mossmoss.lessonisland.UpdateArchiveSigningTest')) -join ',')
    $taskArgs=@('-e','class',$taskClasses)
    New-Item -ItemType Directory -Path 'test-results' -Force | Out-Null
    $taskResult=& $taskAdb -s $Device shell am instrument -w @taskArgs top.mossmoss.lessonisland.test/android.test.InstrumentationTestRunner
    $taskResult | Tee-Object -FilePath ('test-results\'+$Device+'-instrumentation.txt')
    if(($taskResult -join "`n") -notmatch 'OK \(\d+ tests\)'){throw 'Instrumentation tests failed.'}
    & $taskAdb -s $Device pull /sdcard/Android/data/top.mossmoss.lessonisland/files/test-screenshots ('test-results\'+$Device+'-screenshots')
} finally { Pop-Location }
