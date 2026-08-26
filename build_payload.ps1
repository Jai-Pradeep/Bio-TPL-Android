# Configuration
$JAVA_BIN = "C:\Program Files\Android\Android Studio\jbr\bin"
$KOTLINC = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$D8 = "C:\Users\Lenovo\AppData\Local\Android\Sdk\build-tools\36.0.0\d8.bat"
$ANDROID_JAR = "C:\Users\Lenovo\AppData\Local\Android\Sdk\platforms\android-35\android.jar"

# Add Java to PATH for this session so kotlinc/d8 can find it
$env:PATH = "$JAVA_BIN;" + $env:PATH

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

# 3. Convert to DEX (Only include the DynamicPayload class)
Write-Host "Converting to DEX..." -ForegroundColor Cyan
$CLASS_FILE = Join-Path $TEMP_DIR "com\example\face_auth_app\DynamicPayload.class"

if (-not (Test-Path $CLASS_FILE)) {
    Write-Host "Error: DynamicPayload.class not found at $CLASS_FILE" -ForegroundColor Red
    Write-Host "Listing generated files:"
    Get-ChildItem -Path $TEMP_DIR -Recurse -Filter *.class
    exit 1
}

& $D8 $CLASS_FILE --output $SCRATCH_DIR --lib $ANDROID_JAR

if ($LASTEXITCODE -ne 0) {
    Write-Host "DEX conversion failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 4. Rename classes.dex to payload.dex
$DEX_OUT = Join-Path $SCRATCH_DIR "classes.dex"
if (Test-Path $DEX_OUT) {
    Move-Item $DEX_OUT (Join-Path $SCRATCH_DIR "payload.dex") -Force
}

# 5. Cleanup
Remove-Item -Recurse -Force $TEMP_DIR
Write-Host "Success! payload.dex created in $SCRATCH_DIR" -ForegroundColor Green
