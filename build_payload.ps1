# Configuration
$KOTLINC = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$D8 = "C:\Users\Lenovo\AppData\Local\Android\Sdk\build-tools\36.0.0\d8.bat"
$ANDROID_JAR = "C:\Users\Lenovo\AppData\Local\Android\Sdk\platforms\android-34\android.jar" # Adjust if needed

# Paths
$SCRATCH_DIR = $PSScriptRoot
$APP_SRC_DIR = "D:\Jai\IITT\Sem 7\face-auth\app\src\main\java\com\example\face_auth_app"

Write-Host "--- Starting Payload Build ---" -ForegroundColor Cyan

# 1. Create a temporary build directory
$TEMP_DIR = Join-Path $SCRATCH_DIR "temp_build"
if (Test-Path $TEMP_DIR) { Remove-Item -Recurse -Force $TEMP_DIR }
New-Item -ItemType Directory -Path $TEMP_DIR | Out-Null

# 2. Compile Kotlin files
Write-Host "Compiling Kotlin files..." -ForegroundColor Cyan
& $KOTLINC "$SCRATCH_DIR\Payload.kt" "$APP_SRC_DIR\IPayload.kt" -d $TEMP_DIR -cp $ANDROID_JAR

if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 3. Convert to DEX
Write-Host "Converting to DEX..." -ForegroundColor Cyan
$CLASS_FILES = Get-ChildItem -Path $TEMP_DIR -Filter *.class -Recurse | Select-Object -ExpandProperty FullName
& $D8 $CLASS_FILES --output $SCRATCH_DIR --lib $ANDROID_JAR

if ($LASTEXITCODE -ne 0) {
    Write-Host "DEX conversion failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 4. Cleanup
Remove-Item -Recurse -Force $TEMP_DIR
Write-Host "Success! payload.dex created in $SCRATCH_DIR" -ForegroundColor Green
Write-Host "Copy this file to your server directory if it's different." -ForegroundColor Yellow
