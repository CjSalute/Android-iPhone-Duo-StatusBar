# IOSDuo module Windows build script (ASCII only: PS 5.1 reads ANSI)
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
$SDK  = Join-Path $ROOT '..\sdk'
$BAD  = Join-Path $ROOT 'build'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\Tools\jdk25' }
$JAVA_HOME = $env:JAVA_HOME
$JAVAC = Join-Path $JAVA_HOME 'bin\javac.exe'
$JAR   = Join-Path $JAVA_HOME 'bin\jar.exe'

if (Test-Path $BAD) { Remove-Item -Recurse -Force $BAD }
New-Item -ItemType Directory -Path "$BAD\stubs","$BAD\classes","$BAD\dex" | Out-Null

function Expand-Tool($zip, $dest) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Path $dest | Out-Null
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}
$btDir = Join-Path $SDK 'build-tools'
$pfDir = Join-Path $SDK 'platform'
Expand-Tool (Join-Path $SDK 'bt36.zip') $btDir
Expand-Tool (Join-Path $SDK 'platform36.zip') $pfDir

$aapt2     = (Get-ChildItem $btDir -Recurse -Filter aapt2.exe     | Select-Object -First 1).FullName
$zipalign  = (Get-ChildItem $btDir -Recurse -Filter zipalign.exe  | Select-Object -First 1).FullName
$apksigner = (Get-ChildItem $btDir -Recurse -Filter apksigner.bat | Select-Object -First 1).FullName
$d8bat     = (Get-ChildItem $btDir -Recurse -Filter d8.bat       | Select-Object -First 1).FullName
$androidJar= (Get-ChildItem $pfDir -Recurse -Filter android.jar  | Sort-Object Length -Descending | Select-Object -First 1).FullName

foreach ($p in @($aapt2,$zipalign,$apksigner,$d8bat,$androidJar)) {
    if (-not $p) { throw "SDK component missing: unzip sdk/bt36.zip and sdk/platform36.zip" }
}
Write-Host "aapt2      : $aapt2"
Write-Host "d8         : $d8bat"
Write-Host "android.jar: $androidJar"

Write-Host "== 1. compile xposed stubs"
$stubFiles = Get-ChildItem (Join-Path $ROOT 'stubs') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC -nowarn -encoding UTF-8 -d "$BAD\stubs" @stubFiles
if ($LASTEXITCODE -ne 0) { throw "stubs javac failed" }
& $JAR cf "$BAD\stubs.jar" -C "$BAD\stubs" .
if ($LASTEXITCODE -ne 0) { throw "stubs jar failed" }

Write-Host "== 2. aapt2 link"
& $aapt2 compile --dir (Join-Path $ROOT 'res') -o "$BAD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& $aapt2 link -o "$BAD\base.apk" --manifest (Join-Path $ROOT 'AndroidManifest.xml') `
    -I $androidJar -A (Join-Path $ROOT 'assets') "$BAD\res.zip" `
    --min-sdk-version 24 --target-sdk-version 36
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 3. compile module"
$srcFiles = Get-ChildItem (Join-Path $ROOT 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$cp = "$androidJar;$BAD\stubs.jar"
$ok = $false
foreach ($rel in @('8','11','17')) {
    & $JAVAC --release $rel -nowarn -encoding UTF-8 -classpath $cp -d "$BAD\classes" @srcFiles
    if ($LASTEXITCODE -eq 0) { Write-Host "   javac --release $rel OK"; $ok = $true; break }
}
if (-not $ok) { throw "javac failed for release 8/11/17" }
& $JAR cf "$BAD\classes.jar" -C "$BAD\classes" .
if ($LASTEXITCODE -ne 0) { throw "classes jar failed" }

Write-Host "== 4. d8"
& $d8bat --release --min-api 24 --lib $androidJar --output "$BAD\dex" "$BAD\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== 5. pack dex"
$py = 'C:\Users\65401\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$packPy = Join-Path $BAD 'pack.py'
$pySrc = @(
 'import sys, zipfile',
 'base, out, dex = sys.argv[1], sys.argv[2], sys.argv[3]',
 'zi = zipfile.ZipFile(base, "r")',
 'zo = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)',
 'for it in zi.infolist():',
 '    zo.writestr(it, zi.read(it.filename))',
 'zo.write(dex, "classes.dex")',
 'zo.close(); zi.close()',
 'print("packed", out)'
)
Set-Content -Path $packPy -Value $pySrc -Encoding ASCII
& $py $packPy "$BAD\base.apk" "$BAD\unsigned.apk" "$BAD\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw "pack failed" }

Write-Host "== 6. zipalign + sign"
& $zipalign -f 4 "$BAD\unsigned.apk" "$BAD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = $env:IOSDUO_KS; if (-not $ks) { $ks = Join-Path $ROOT 'iosduo.jks' }
$ksPass = $env:IOSDUO_KS_PASS; if (-not $ksPass) { throw 'set IOSDUO_KS_PASS (keystore password)' }
$out = Join-Path $ROOT 'IOSDuo-ThreeInOne.apk'
if (Test-Path $out) { Remove-Item -Force $out }
& $apksigner sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" --out $out "$BAD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $apksigner verify $out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Get-Item $out | Format-List FullName, Length, LastWriteTime
Write-Host "SIGNED OK"
