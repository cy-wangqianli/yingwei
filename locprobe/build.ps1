# locprobe build script (no Gradle). Run from locprobe directory.
# Uses the same toolchain / signing key as the main app (build shadowloc first).
param(
    [string]$SdkRoot = "",
    [string]$JdkHome = "",
    [string]$KsPass = "shadowloc123"
)

$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$workspace = Split-Path -Parent $proj

if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    if ($env:ANDROID_HOME) { $SdkRoot = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkRoot = $env:ANDROID_SDK_ROOT }
    else { $SdkRoot = Join-Path $workspace ".toolchains\android-sdk" }
}
if (!(Test-Path $SdkRoot)) {
    throw "Android SDK not found at '$SdkRoot'. Pass -SdkRoot <path> or set ANDROID_HOME."
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) { $JdkHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($JdkHome) -or !(Test-Path (Join-Path $JdkHome "bin\javac.exe"))) {
    throw "JDK not found. Pass -JdkHome <path> or set JAVA_HOME."
}

# 把 SDK 转成相对工程目录的路径：aapt2 无法打开含非 ASCII 字符的绝对路径
$sdkRel = [uri]::UnescapeDataString(
    ([uri]($proj.TrimEnd('\') + '\')).MakeRelativeUri([uri]$SdkRoot).ToString()
).Replace('/', '\')

$bt = Join-Path $SdkRoot "build-tools\35.0.0"
$aapt2 = Join-Path $bt "aapt2.exe"
$d8 = Join-Path $bt "d8.bat"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$javac = Join-Path $JdkHome "bin\javac.exe"
$jar = Join-Path $JdkHome "bin\jar.exe"
$androidJar = Join-Path $SdkRoot "platforms\android-35\android.jar"

foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $androidJar)) {
    if (!(Test-Path $tool)) { throw "missing tool: $tool" }
}
$ErrorActionPreference = "Continue"

Push-Location $proj
try {
    Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path build\gen, build\classes, build\dex, build\out | Out-Null

    & $aapt2 compile --dir res -o build\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    & $aapt2 link -o build\base.apk -I "$sdkRel\platforms\android-35\android.jar" `
        --manifest AndroidManifest.xml --java build\gen --min-sdk-version 26 --target-sdk-version 34 `
        --auto-add-overlay build\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    $javaFiles = @()
    $javaFiles += (Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { $_.FullName.Substring($proj.Length + 1) })
    $javaFiles += (Get-ChildItem build\gen -Recurse -Filter *.java | ForEach-Object { $_.FullName.Substring($proj.Length + 1) })
    & $javac -encoding UTF-8 -source 11 -target 11 -nowarn -classpath "$sdkRel\platforms\android-35\android.jar" -d build\classes $javaFiles
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    & $jar cf build\classes.jar -C build\classes .
    & $d8 --lib "$sdkRel\platforms\android-35\android.jar" --min-api 26 --output build\dex build\classes.jar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    $stage = Join-Path $proj "build\stage"
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $proj "build\base.apk"), $stage)
    Copy-Item (Join-Path $proj "build\dex\classes.dex") (Join-Path $stage "classes.dex") -Force
    & $jar cf0 (Join-Path $proj "build\out\locprobe-unsigned.apk") -C $stage .
    if ($LASTEXITCODE -ne 0) { throw "jar assemble failed" }

    & $zipalign -f 4 build\out\locprobe-unsigned.apk build\out\locprobe-aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    # 复用主 App 生成的签名密钥（locprobe 不自己生成）
    $ks = "..\shadowloc\keystore\shadowloc.jks"
    if (!(Test-Path $ks)) {
        throw "Signing key not found at '$ks'. Build shadowloc first; it generates the keystore."
    }
    & $apksigner sign --ks $ks --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" `
        --min-sdk-version 26 --out build\out\locprobe-v1.0.0.apk build\out\locprobe-aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    New-Item -ItemType Directory -Force -Path dist | Out-Null
    Copy-Item build\out\locprobe-v1.0.0.apk dist\locprobe-v1.0.0.apk -Force

    Write-Host ("built: " + (Join-Path $proj "dist\locprobe-v1.0.0.apk"))
    & $aapt2 dump badging dist\locprobe-v1.0.0.apk | Select-Object -First 2
} finally {
    Pop-Location
}
