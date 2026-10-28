$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
Push-Location $projectPath
try {
    & .\gradlew.bat :app:assembleGenericDebug -PdailyBuild=true --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' '-Pkotlin.daemon.jvmargs=-Xmx2g' --console=plain @args
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    $destination = Join-Path $projectPath 'dist'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectPath 'app\build\outputs\apk\generic\debug\app-generic-debug.apk') -Destination (Join-Path $destination 'daily-planner-text-import-v1.apk') -Force
    Write-Host "APK: $destination\daily-planner-text-import-v1.apk"
} finally { Pop-Location }
