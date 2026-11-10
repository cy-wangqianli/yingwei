# shadowloc build script (no Gradle)
# Requires: JDK 17+ and Android SDK build-tools 35.0.0 + platforms;android-35
# NOTE: all tool arguments are RELATIVE paths on purpose. aapt2 on Windows cannot
# open directories whose path contains non-ASCII characters, so we run with the
# project directory as CWD and never pass absolute non-ASCII paths to it.
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
$androidJar = Join-Path $SdkRoot "platforms\android-35\android.jar"
$javac = Join-Path $JdkHome "bin\javac.exe"
$jar = Join-Path $JdkHome "bin\jar.exe"
$keytool = Join-Path $JdkHome "bin\keytool.exe"

foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $androidJar, $javac, $jar, $keytool)) {
    if (!(Test-Path $tool)) { throw "missing tool: $tool" }
}

# 原生工具会往 stderr 写提示信息，不能让 PowerShell 把它当成终止错误；失败由 $LASTEXITCODE 判断
$ErrorActionPreference = "Continue"

Push-Location $proj
try {
    Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path build\gen, build\classes, build\dex, build\out | Out-Null

    Write-Host "[1/7] aapt2 compile"
    & $aapt2 compile --dir res -o build\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    Write-Host "[2/7] aapt2 link (manifest + resources + assets)"
    & $aapt2 link -o build\base.apk -I "$sdkRel\platforms\android-35\android.jar" `
        --manifest AndroidManifest.xml --java build\gen --min-sdk-version 26 --target-sdk-version 34 `
        --auto-add-overlay -A assets build\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    Write-Host "[3/7] javac"
    $javaFiles = @()
    $javaFiles += (Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { $_.FullName.Substring($proj.Length + 1) })
    $javaFiles += (Get-ChildItem build\gen -Recurse -Filter *.java | ForEach-Object { $_.FullName.Substring($proj.Length + 1) })
    & $javac -encoding UTF-8 -source 11 -target 11 -nowarn -classpath "$sdkRel\platforms\android-35\android.jar" -d build\classes $javaFiles
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    Write-Host "[4/7] d8 (dex)"
    & $jar cf build\classes.jar -C build\classes .
    & $d8 --lib "$sdkRel\platforms\android-35\android.jar" --min-api 26 --output build\dex build\classes.jar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    Write-Host "[5/7] assemble APK (all entries stored, so resources.arsc stays uncompressed)"
    # .NET 的 CompressionLevel.NoCompression 实际仍是 deflate(level 0)，会让 resources.arsc
    # 被判为压缩而安装失败（targetSdk>=30）。jar 的 -0 才是真正的 STORED。
    $stage = Join-Path $proj "build\stage"
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $proj "build\base.apk"), $stage)
    Copy-Item (Join-Path $proj "build\dex\classes.dex") (Join-Path $stage "classes.dex") -Force
    $unsigned = Join-Path $proj "build\out\shadowloc-unsigned.apk"
    & $jar cf0 $unsigned -C $stage .
    if ($LASTEXITCODE -ne 0) { throw "jar assemble failed" }

    Write-Host "[6/7] zipalign"
    & $zipalign -f 4 build\out\shadowloc-unsigned.apk build\out\shadowloc-aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    Write-Host "[7/7] sign"
    $ks = "keystore\shadowloc.jks"
    if (!(Test-Path $ks)) {
        New-Item -ItemType Directory -Force -Path keystore | Out-Null
        & $keytool -genkeypair -keystore $ks -alias shadowloc -keyalg RSA -keysize 2048 `
            -validity 10000 -storepass $KsPass -keypass $KsPass `
            -dname "CN=ShadowLoc, O=DSH, C=CN"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    }
    & $apksigner sign --ks $ks --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" `
        --min-sdk-version 26 --out build\out\shadowloc-v1.0.0.apk build\out\shadowloc-aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    New-Item -ItemType Directory -Force -Path dist | Out-Null
    Copy-Item build\out\shadowloc-v1.0.0.apk dist\shadowloc-v1.0.0.apk -Force

    Write-Host ""
    Write-Host ("built: " + (Join-Path $proj "dist\shadowloc-v1.0.0.apk"))
    Write-Host ("size: {0:N1} KB" -f ((Get-Item dist\shadowloc-v1.0.0.apk).Length / 1KB))
    & $apksigner verify --print-certs dist\shadowloc-v1.0.0.apk
    & $aapt2 dump badging dist\shadowloc-v1.0.0.apk | Select-Object -First 4
} finally {
    Pop-Location
}
