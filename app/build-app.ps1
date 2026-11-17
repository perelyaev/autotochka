# Собирает APK приложения «Автоточка» без Gradle.
#
#   powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Tools "C:\android-tools"
#   powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Tools "C:\android-tools" -Install
#
# -Tools: каталог с aapt2.exe, android-33.jar, zipalign.exe и подпапкой lib
#         (lib\d8.jar, lib\apksigner.jar).
param(
    [Parameter(Mandatory = $true)][string]$Tools,
    [switch]$Install,
    [string]$VersionName = '1.0.0',
    [int]$VersionCode = 1
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$aapt2 = Join-Path $Tools 'aapt2.exe'
$androidJar = Join-Path $Tools 'android-35.jar'
if (-not (Test-Path $androidJar)) { $androidJar = Join-Path $Tools 'android-33.jar' }
$targetSdk = if ($androidJar -match 'android-(\d+)') { [int]$matches[1] } else { 33 }
$d8 = Join-Path $Tools 'lib\d8.jar'
$apksigner = Join-Path $Tools 'lib\apksigner.jar'
$zipalign = Join-Path $Tools 'zipalign.exe'

foreach ($p in @($aapt2, $androidJar, $d8, $apksigner, $zipalign)) {
    if (-not (Test-Path $p)) { throw "не найден инструмент: $p" }
}

$build = Join-Path $root 'build'
$out = Join-Path $root 'dist'
$res = Join-Path $root 'res'
$src = Join-Path $root 'src'
$manifest = Join-Path $root 'AndroidManifest.xml'

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex'), $out | Out-Null

Write-Host '1/7 ресурсы'
& $aapt2 compile --dir $res -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '2/7 линковка'
& $aapt2 link -o (Join-Path $build 'app-unsigned.apk') -I $androidJar `
    --manifest $manifest --min-sdk-version 26 --target-sdk-version $targetSdk `
    --version-code $VersionCode --version-name $VersionName `
    --java (Join-Path $build 'gen') (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '3/7 компиляция Java'
$javaFiles = @(Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem -Path (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$hab = Join-Path $root 'lib\hiddenapibypass-4.3.jar'
$cp = "$androidJar;$hab"
& javac --release 11 -classpath $cp -d (Join-Path $build 'classes') $javaFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '4/7 dex'
$classFiles = @(Get-ChildItem -Path (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& java -cp $d8 com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output (Join-Path $build 'dex') ($classFiles + $hab)
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '5/7 упаковка'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $build 'app-unsigned.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $build 'dex\classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

Write-Host '6/7 выравнивание'
$aligned = Join-Path $build 'app-aligned.apk'
& $zipalign -f 4 $apkPath $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '7/7 подпись'
$keys = Join-Path $root 'keys'
New-Item -ItemType Directory -Force -Path $keys | Out-Null
$ks = Join-Path $keys 'autotochka.jks'
$passFile = Join-Path $keys 'password.txt'
if (-not (Test-Path $ks)) {
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { throw 'keytool не найден в PATH' }
    $pass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    & $keytool -genkeypair -keystore $ks -alias autotochka -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $pass -keypass $pass -dname 'CN=Autotochka, O=Baikalteam, C=RU' 2>&1 | Out-Null
    Set-Content -Path $passFile -Value $pass -Encoding UTF8
    Write-Host "Создан ключ подписи: $ks"
    Write-Host "Пароль сохранён в $passFile — сохраните его, без него не выпустить обновление"
}
$pass = (Get-Content $passFile -Raw).Trim()
$final = Join-Path $out 'autotochka.apk'
& java -jar $apksigner sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" --out $final $aligned 2>&1 | Out-Null
if (-not (Test-Path $final)) { throw 'apksigner failed' }

Write-Host "Готово: $final ($([math]::Round((Get-Item $final).Length / 1KB)) КБ)"

if ($Install) {
    & adb install -g -r $final
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    Write-Host 'Установлено на телефон'
}
