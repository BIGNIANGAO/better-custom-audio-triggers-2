param(
    [string]$GameDir = $env:BATTLEAUDIO_GAME_DIR,
    [string]$WorkshopDir = $env:BATTLEAUDIO_WORKSHOP_DIR,
    [string]$JdkDir = $env:BATTLEAUDIO_JDK_DIR,
    [string]$OutputRoot,
    [switch]$BundleDecoder,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
foreach ($required in @(@{Name='GameDir';Value=$GameDir;Env='BATTLEAUDIO_GAME_DIR'},@{Name='WorkshopDir';Value=$WorkshopDir;Env='BATTLEAUDIO_WORKSHOP_DIR'},@{Name='JdkDir';Value=$JdkDir;Env='BATTLEAUDIO_JDK_DIR'})) {
    if ([string]::IsNullOrWhiteSpace($required.Value)) { throw "Pass -$($required.Name) or set $($required.Env)" }
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$gameJar = Join-Path $GameDir 'desktop-1.0.jar'
$baseMod = Join-Path $WorkshopDir '1605833019\BaseMod.jar'
$modTheSpire = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
foreach ($dependency in @($gameJar, $baseMod, $modTheSpire)) {
    if (!(Test-Path -LiteralPath $dependency)) { throw "Missing dependency: $dependency" }
}
$classPath = "$gameJar;$baseMod;$modTheSpire"
if (!$OutputRoot) { $OutputRoot = Join-Path $projectRoot 'build' }
$buildRoot = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
$classes = Join-Path $buildRoot ('classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkDir 'bin\javac.exe') --release 8 -g -Xlint:-options -encoding UTF-8 -classpath $classPath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
Copy-Item -Path (Join-Path $projectRoot 'src\main\resources\*') -Destination $classes -Recurse -Force
# Public builds contain no embedded audio or preset resources.
if (!$SkipTests) {
    $tests = Join-Path $buildRoot 'tests'
    New-Item -ItemType Directory -Force -Path $tests | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $JdkDir 'bin\javac.exe') --release 8 -g -Xlint:-options -encoding UTF-8 -classpath "$classes;$classPath" -d $tests @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    $decoder = (Get-Command ffmpeg.exe -ErrorAction Stop).Source
    $gameJava = Join-Path $GameDir 'jre\bin\java.exe'
    & $gameJava '-Dfile.encoding=UTF-8' -classpath "$tests;$classes;$classPath" battleaudio.AllTests $decoder $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
    & $gameJava '-Dfile.encoding=UTF-8' -classpath "$tests;$(Join-Path $GameDir 'mod-uploader.jar')" battleaudio.UploaderEncodingSmoke $buildRoot
    if ($LASTEXITCODE -ne 0) { throw 'Uploader encoding verification failed' }
    $fixtureRoot = Join-Path $buildRoot 'fixtures'
    New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
    function Write-TestTone([string]$path, [int]$frequency) {
        $sampleRate = 44100; $sampleCount = $sampleRate
        $stream = [IO.File]::Create($path); $writer = New-Object IO.BinaryWriter($stream)
        try {
            $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $writer.Write([int](36 + $sampleCount * 2)); $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
            $writer.Write([int]16); $writer.Write([int16]1); $writer.Write([int16]1); $writer.Write([int]$sampleRate); $writer.Write([int]($sampleRate * 2)); $writer.Write([int16]2); $writer.Write([int16]16)
            $writer.Write([Text.Encoding]::ASCII.GetBytes('data')); $writer.Write([int]($sampleCount * 2))
            for ($index = 0; $index -lt $sampleCount; $index++) { $writer.Write([int16]([Math]::Round(12000 * [Math]::Sin(2 * [Math]::PI * $frequency * $index / $sampleRate)))) }
        } finally { $writer.Dispose(); $stream.Dispose() }
    }
    $samplePath = Join-Path $fixtureRoot 'test-tone.wav'
    Write-TestTone $samplePath 440
    & $gameJava '-Dfile.encoding=UTF-8' -classpath "$tests;$classes;$classPath" battleaudio.DesktopSmoke $decoder (Join-Path $buildRoot 'desktop-check') $samplePath
    if ($LASTEXITCODE -ne 0) { throw 'Desktop audio smoke verification failed' }
    & (Join-Path $projectRoot 'scripts\test-workshop-publisher.ps1') -GameDir $GameDir -WorkRoot $buildRoot
    if (!$?) { throw 'Workshop publisher prepare-only tests failed' }
}
$dist = if ($OutputRoot -eq (Join-Path $projectRoot 'build')) { Join-Path $projectRoot 'dist' } else { Join-Path $buildRoot 'dist' }
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$modVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'src\main\resources\ModTheSpire.json') -Raw -Encoding UTF8 | ConvertFrom-Json).version
$jar = Join-Path $dist ('BattleAudio-' + $modVersion + '.jar')
# Package compiled sources explicitly, so a previous bundled build cannot leak its decoder into a development jar.
if ($BundleDecoder) {
    $decoder = (Get-Command ffmpeg.exe -ErrorAction Stop).Source
    $bin = Join-Path $buildRoot 'bundle\battleaudio\bin'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    Copy-Item -LiteralPath $decoder -Destination (Join-Path $bin 'ffmpeg.exe') -Force
    $decoderRoot = Split-Path (Split-Path $decoder -Parent) -Parent
    Copy-Item -LiteralPath (Join-Path $decoderRoot 'LICENSE') -Destination (Join-Path $bin 'COPYING.txt') -Force
    Copy-Item -LiteralPath (Join-Path $decoderRoot 'README.txt') -Destination (Join-Path $bin 'BUILD-INFO.txt') -Force
    & (Join-Path $JdkDir 'bin\jar.exe') cf $jar -C $classes . -C (Join-Path $buildRoot 'bundle') battleaudio
} else {
    & (Join-Path $JdkDir 'bin\jar.exe') cf $jar -C $classes .
}
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
Get-Item -LiteralPath $jar | Select-Object FullName, Length
Get-FileHash -LiteralPath $jar -Algorithm SHA256
if (!$SkipTests) {
    $patchOutput = Join-Path $buildRoot 'patch-check'
    New-Item -ItemType Directory -Force -Path $patchOutput | Out-Null
    Push-Location $patchOutput
    try {
        & $gameJava '-Dfile.encoding=UTF-8' -classpath "$tests;$modTheSpire" battleaudio.PatchSmoke $gameJar $baseMod $jar $patchOutput
        if ($LASTEXITCODE -ne 0) { throw 'ModTheSpire patch verification failed' }
    } finally { Pop-Location }
}
