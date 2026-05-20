$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcRoot = Join-Path $root 'src'
$buildRoot = Join-Path $root 'build'
$classesDir = Join-Path $buildRoot 'classes'
$libDir = Join-Path $root 'lib'
$distDir = Join-Path $root 'dist'

New-Item -ItemType Directory -Force -Path $classesDir, $libDir, $distDir | Out-Null

$byteBuddyVersion = '1.18.8'
$ecjVersion = '3.45.0'
$byteBuddyJar = Join-Path $libDir "byte-buddy-$byteBuddyVersion.jar"
$ecjJar = Join-Path $libDir "ecj-$ecjVersion.jar"

if (!(Test-Path $byteBuddyJar)) {
    Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/$byteBuddyVersion/byte-buddy-$byteBuddyVersion.jar" -OutFile $byteBuddyJar
}
if (!(Test-Path $ecjJar)) {
    Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$ecjVersion/ecj-$ecjVersion.jar" -OutFile $ecjJar
}

Remove-Item -Recurse -Force $classesDir
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$javaFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $javaFiles) {
    throw "No Java sources found under $srcRoot"
}

$compileArgs = @(
    '-jar'
    $ecjJar
    '-source'
    '17'
    '-target'
    '17'
    '-classpath'
    $byteBuddyJar
    '-d'
    $classesDir
) + $javaFiles

& 'C:\Program Files\PokeMMO\jre\bin\java.exe' @compileArgs

if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed with exit code $LASTEXITCODE"
}

$manifest = @"
Manifest-Version: 1.0
Premain-Class: io.pokemmo.hook.PokemonStatsAgent
Agent-Class: io.pokemmo.hook.PokemonStatsAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
Class-Path: ../lib/byte-buddy-$byteBuddyVersion.jar

"@

$manifestPath = Join-Path $buildRoot 'MANIFEST.MF'
Set-Content -LiteralPath $manifestPath -Value $manifest -NoNewline

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jarPath = Join-Path $distDir "pokemmo-pvp-hook-agent-$stamp.jar"

$packScriptPath = Join-Path $buildRoot 'pack_jar.py'
$packScript = @'
import sys, zipfile, pathlib
jar_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
classes_dir = pathlib.Path(sys.argv[3])
with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(manifest_path, 'META-INF/MANIFEST.MF')
    for path in classes_dir.rglob('*'):
        if path.is_file():
            z.write(path, path.relative_to(classes_dir).as_posix())
'@
Set-Content -LiteralPath $packScriptPath -Value $packScript -NoNewline
python $packScriptPath $jarPath $manifestPath $classesDir

if ($LASTEXITCODE -ne 0) {
    throw "Jar packaging failed with exit code $LASTEXITCODE"
}

Write-Host "Built agent jar: $jarPath"
