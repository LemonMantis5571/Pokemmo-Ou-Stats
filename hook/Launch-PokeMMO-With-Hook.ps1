$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$distDir = Join-Path $root 'dist'
$libDir = Join-Path $root 'lib'
$logsDir = Join-Path $root 'logs'
$jdkRoot = Join-Path $root 'runtime'
$logPath = Join-Path $logsDir 'pvp-stats.jsonl'
$gameDir = 'C:\Program Files\PokeMMO'
$gameJar = Join-Path $gameDir 'PokeMMO.exe'
$mainClass = 'com.pokeemu.client.Client'

function Resolve-Java {
    $existing = Get-ChildItem -Path $jdkRoot -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if ($existing) {
        return $existing.FullName
    }

    New-Item -ItemType Directory -Force -Path $jdkRoot | Out-Null
    $api = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?os=windows&architecture=x64&image_type=jdk&jvm_impl=hotspot&heap_size=normal&vendor=eclipse'
    $asset = (Invoke-WebRequest -UseBasicParsing $api -TimeoutSec 60).Content | ConvertFrom-Json | Select-Object -First 1
    $zipName = $asset.binary.package.name
    $zipPath = Join-Path $libDir $zipName
    if (!(Test-Path $zipPath)) {
        New-Item -ItemType Directory -Force -Path $libDir | Out-Null
        Invoke-WebRequest -UseBasicParsing $asset.binary.package.link -OutFile $zipPath
    }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $jdkRoot -Force
    $java = Get-ChildItem -Path $jdkRoot -Recurse -Filter java.exe |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if (!$java) {
        throw "Could not locate java.exe after extracting portable JDK."
    }
    return $java.FullName
}

if (!(Get-ChildItem -LiteralPath $distDir -Filter 'pokemmo-pvp-hook-agent-*.jar' -ErrorAction SilentlyContinue)) {
    & (Join-Path $root 'build-hook.ps1')
}

$agentJar = Get-ChildItem -LiteralPath $distDir -Filter 'pokemmo-pvp-hook-agent-*.jar' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName

if (!$agentJar) {
    throw "Agent jar still missing after build."
}

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$javaExe = Resolve-Java

$args = @(
    "-javaagent:$agentJar"
    "-Dpokemmo.hook.log=$logPath"
    '-Xms128M'
    '-Xmx384M'
    '-Dfile.encoding=UTF-8'
    '-cp'
    $gameJar
    $mainClass
)

Write-Host "Launching with:"
Write-Host "  Java: $javaExe"
Write-Host "  Agent: $agentJar"
Write-Host "  Log: $logPath"

function Quote-Arg([string]$arg) {
    if ($arg -notmatch '[\s"]') {
        return $arg
    }
    $escaped = $arg -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $javaExe
$psi.WorkingDirectory = $gameDir
$psi.UseShellExecute = $false
$psi.Arguments = ($args | ForEach-Object { Quote-Arg $_ }) -join ' '
[System.Diagnostics.Process]::Start($psi) | Out-Null
