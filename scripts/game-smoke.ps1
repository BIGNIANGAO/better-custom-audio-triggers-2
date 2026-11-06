param(
    [string]$GameDir = $env:BATTLEAUDIO_GAME_DIR,
    [string]$WorkshopDir = $env:BATTLEAUDIO_WORKSHOP_DIR,
    [string]$JdkDir = $env:BATTLEAUDIO_JDK_DIR,
    [string]$BuildRoot,
    [switch]$ReproLoad,
    [switch]$ContinueSave,
    [switch]$UiSmoke,
    [switch]$ConfigSmoke,
    [switch]$SliderSmoke,
    [switch]$EventsSmoke,
    [switch]$FreshEventsSmoke,
    [switch]$KillFilterSmoke,
    [switch]$EncounterSmoke,
    [switch]$EncounterUiCombatSmoke,
    [switch]$Fullscreen,
    [switch]$StopProbabilitySmoke,
    [switch]$PrerequisiteSmoke,
    [switch]$BackgroundMixSmoke,
    [switch]$BundledDecoderSmoke,
    [switch]$DeathSmoke,
    [switch]$CandidateSmoke,
    [switch]$FlowSmoke,
    [switch]$StyleSmoke,
    [switch]$NativeStyle,
    [switch]$FilterSmoke,
    [switch]$LocalizationSmoke,
    [switch]$SettingsPerformanceSmoke,
    [switch]$SettingsPerformanceMinimal,
    [switch]$GremlinSummonSmoke,
    [switch]$PrivateWorkshopReadSmoke,
    [switch]$LoongPerformanceSmoke,
    [string]$PrivateWorkshopPath,
    [switch]$AllLanguageFontSmoke,
    [ValidateSet("ENG","DUT","EPO","PTB","ZHS","ZHT","FIN","FRA","DEU","GRE","IND","ITA","JPN","KOR","NOR","POL","RUS","SPA","SRP","SRB","THA","TUR","UKR","VIE")]
    [string]$Language,
    [string]$ModJar,
    [int]$Width = 1280,
    [int]$Height = 720,
    [string]$SaveDirectory,
    [string]$AudioDirectory,
    [string]$ExpectName
)
$ErrorActionPreference = 'Stop'
foreach ($required in @(@{Name='GameDir';Value=$GameDir;Env='BATTLEAUDIO_GAME_DIR'},@{Name='WorkshopDir';Value=$WorkshopDir;Env='BATTLEAUDIO_WORKSHOP_DIR'},@{Name='JdkDir';Value=$JdkDir;Env='BATTLEAUDIO_JDK_DIR'})) {
    if ([string]::IsNullOrWhiteSpace($required.Value)) { throw "Pass -$($required.Name) or set $($required.Env)" }
}
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$BuildRoot) { $BuildRoot = Join-Path $projectRoot 'build' }
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$output = Join-Path $BuildRoot ('game-smoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output | Out-Null
$fakeWorkshop = Join-Path $output 'steamapps\workshop\content\646570'
New-Item -ItemType Directory -Force -Path $fakeWorkshop | Out-Null
$installed = if ($SettingsPerformanceSmoke -and !$SettingsPerformanceMinimal) { '"999999991" { "manifest" "1" }' } else { '' }
$state = '"AppWorkshop" { "appid" "646570" "NeedsDownload" "0" "NeedsUpdate" "0" "WorkshopItemsInstalled" { ' + $installed + ' } }'
[IO.File]::WriteAllText((Join-Path $output 'steamapps\workshop\appworkshop_646570.acf'), $state, [Text.UTF8Encoding]::new($false))
if ($installed) {
    $fakeItem = Join-Path $fakeWorkshop '999999991'
    New-Item -ItemType Directory -Force -Path $fakeItem | Out-Null
    $fakeBundle = @{schemaVersion=1; packageId='ui-smoke-package'; workshopId=''; name='UI subscription fixture'; version='1'; enabled=$true; rules=@(@{id='ui-smoke-rule'; name='UI subscribed rule'; triggers=@('PLAYER_DEATH'); candidates=@()}); files=@()}
    [IO.File]::WriteAllText((Join-Path $fakeItem 'music-pack.json'), ($fakeBundle | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
$tests = Join-Path $BuildRoot 'tests'
$launcherClasses = Join-Path $output 'launcher'
New-Item -ItemType Directory -Force -Path (Join-Path $launcherClasses 'battleaudio') | Out-Null
Copy-Item -LiteralPath (Join-Path $tests 'battleaudio\GameLaunchSmoke.class') -Destination (Join-Path $launcherClasses 'battleaudio')
$testJar = Join-Path $output 'smoke-mod.jar'
$meta = @{modid='battleaudio-smoke'; name='Battle Audio Smoke'; version='0.1.0'; author_list=@('Test'); dependencies=@('basemod','battleaudio')} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $output 'ModTheSpire.json'), $meta, [Text.UTF8Encoding]::new($false))
& (Join-Path $JdkDir 'bin\jar.exe') cf $testJar -C $output ModTheSpire.json -C $tests battleaudio
if ($LASTEXITCODE -ne 0) { throw 'Smoke mod packaging failed' }
# All preferences, logs, and mod audio data for this launch live under its isolated working directory.
Copy-Item -LiteralPath (Join-Path $GameDir 'steam_appid.txt') -Destination $output
$preferences = Join-Path $GameDir 'preferences'
if (Test-Path -LiteralPath $preferences) { Copy-Item -LiteralPath $preferences -Destination $output -Recurse }
if ($Language) {
    $languagePrefsPath = Join-Path $output 'preferences\STSGameplaySettings'
    $languagePrefs = if (Test-Path -LiteralPath $languagePrefsPath) { Get-Content -LiteralPath $languagePrefsPath -Raw -Encoding UTF8 | ConvertFrom-Json } else { [pscustomobject]@{} }
    $languagePrefs | Add-Member -NotePropertyName LANGUAGE -NotePropertyValue $Language -Force
    [IO.File]::WriteAllText($languagePrefsPath, ($languagePrefs | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
}
$fullscreenSetting = if ($Fullscreen) { 'true' } else { 'false' }
[IO.File]::WriteAllText((Join-Path $output 'info.displayconfig'), "$Width`n$Height`n60`n$fullscreenSetting`nfalse`ntrue`n", [Text.UTF8Encoding]::new($false))
$mts = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
$baseMod = Join-Path $WorkshopDir '1605833019\BaseMod.jar'
$modVersion = (Get-Content -LiteralPath (Join-Path $projectRoot 'src\main\resources\ModTheSpire.json') -Raw -Encoding UTF8 | ConvertFrom-Json).version
$jar = if ($BuildRoot -eq (Join-Path $projectRoot 'build')) { Join-Path $projectRoot ('dist\BattleAudio-' + $modVersion + '.jar') } else { Join-Path $BuildRoot ('dist\BattleAudio-' + $modVersion + '.jar') }
if ($ModJar) { $jar = (Resolve-Path -LiteralPath $ModJar).Path }
$gameJar = Join-Path $GameDir 'desktop-1.0.jar'
$java = Join-Path $GameDir 'jre\bin\java.exe'
if ($ContinueSave) {
    $saveSource = if ($SaveDirectory) { $SaveDirectory } else { Join-Path $GameDir 'saves' }
    Copy-Item -LiteralPath $saveSource -Destination (Join-Path $output 'saves') -Recurse
}
if ($AudioDirectory) {
    Copy-Item -LiteralPath $AudioDirectory -Destination (Join-Path $output 'audio-data') -Recurse
} elseif (($UiSmoke -or $ConfigSmoke -or $SliderSmoke -or $CandidateSmoke -or $FlowSmoke -or $StyleSmoke -or ($SettingsPerformanceSmoke -and !$SettingsPerformanceMinimal)) -and (Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.battleaudio'))) {
    Copy-Item -LiteralPath (Join-Path $env:USERPROFILE '.battleaudio') -Destination (Join-Path $output 'audio-data') -Recurse
}
$arguments = @('-Dfile.encoding=UTF-8', ('-Dbattleaudio.home="' + (Join-Path $output 'audio-data') + '"'), '-classpath', ('"' + "$launcherClasses;$mts" + '"'), 'battleaudio.GameLaunchSmoke', ('"' + $gameJar + '"'), ('"' + $baseMod + '"'), ('"' + $jar + '"'), ('"' + $testJar + '"'))
if ($PrivateWorkshopReadSmoke) {
    if (!$PrivateWorkshopPath -or !(Test-Path -LiteralPath $PrivateWorkshopPath -PathType Container)) { throw 'A real private Workshop content path is required.' }
    $PrivateWorkshopPath = (Resolve-Path -LiteralPath $PrivateWorkshopPath).Path
    $arguments = @('-Dbattleaudio.privateWorkshopReadSmoke=true', ('-Dbattleaudio.workshopContent="' + $PrivateWorkshopPath + '"')) + $arguments
} elseif ($LoongPerformanceSmoke -and $PrivateWorkshopPath) {
    if (!(Test-Path -LiteralPath $PrivateWorkshopPath -PathType Container)) { throw 'An isolated Workshop fixture is required.' }
    $PrivateWorkshopPath = (Resolve-Path -LiteralPath $PrivateWorkshopPath).Path
    $arguments = @(('-Dbattleaudio.workshopContent="' + $PrivateWorkshopPath + '"')) + $arguments
} else { $arguments = @('-Dbattleaudio.workshopContent="' + $fakeWorkshop + '"') + $arguments }
$fixtures = Join-Path $output 'audio-test-fixtures'
New-Item -ItemType Directory -Force -Path $fixtures | Out-Null
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
if ($LoongPerformanceSmoke) { $arguments = @('-Dbattleaudio.loongPerformanceSmoke=true') + $arguments }
$samplePath = Join-Path $fixtures 'test-tone.wav'; $secondSamplePath = Join-Path $fixtures 'test-tone-2.wav'
Write-TestTone $samplePath 440; Write-TestTone $secondSamplePath 660
Copy-Item -LiteralPath $samplePath -Destination (Join-Path $fixtures 'test-tone-moved.wav')
if ($installed) {
    New-Item -ItemType Directory -Force -Path (Join-Path $fakeItem 'audio') | Out-Null
    Copy-Item -LiteralPath $samplePath -Destination (Join-Path $fakeItem 'audio\tone.wav')
    $fakeBundle.rules[0].candidates = @(@{id='ui-smoke-audio'; name='tone.wav'; sourcePath='audio/tone.wav'; cachePath='audio/tone.wav'; duration=1; start=0; end=1; volume=0.3; backgroundRatio=1; participate=$true})
    [IO.File]::WriteAllText((Join-Path $fakeItem 'music-pack.json'), ($fakeBundle | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
$arguments = @('-Dbattleaudio.sample="' + $samplePath + '"', '-Dbattleaudio.sample2="' + $secondSamplePath + '"') + $arguments
if ($ReproLoad) { $arguments = @('-Dbattleaudio.reproLoad=true') + $arguments }
if ($ContinueSave) { $arguments = @('-Dbattleaudio.continueSave=true') + $arguments }
if ($UiSmoke) { $arguments = @('-Dbattleaudio.uiSmoke=true') + $arguments }
if ($ConfigSmoke) { $arguments = @('-Dbattleaudio.configSmoke=true') + $arguments }
if ($SliderSmoke) { $arguments = @('-Dbattleaudio.sliderSmoke=true') + $arguments }
if ($DeathSmoke) { $arguments = @('-Dbattleaudio.deathSmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($EventsSmoke) { $arguments = @('-Dbattleaudio.eventsSmoke=true') + $arguments }
if ($FreshEventsSmoke) { $arguments = @('-Dbattleaudio.eventsSmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($KillFilterSmoke) { $arguments = @('-Dbattleaudio.killFilterSmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($EncounterSmoke) { $arguments = @('-Dbattleaudio.encounterSmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($EncounterUiCombatSmoke) { $arguments = @('-Dbattleaudio.encounterUiCombatSmoke=true') + $arguments }
if ($Fullscreen) { $arguments = @('-Dbattleaudio.expectedFullscreen=true') + $arguments }
if ($StopProbabilitySmoke) { $arguments = @('-Dbattleaudio.stopProbabilitySmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($PrerequisiteSmoke) { $arguments = @('-Dbattleaudio.prerequisiteSmoke=true', '-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($BackgroundMixSmoke) { $arguments = @('-Dbattleaudio.backgroundMixSmoke=true') + $arguments }
if ($BundledDecoderSmoke) { $arguments = @('-Dbattleaudio.bundledDecoderSmoke=true') + $arguments }
if ($CandidateSmoke) { $arguments = @('-Dbattleaudio.candidateSmoke=true') + $arguments }
if ($FlowSmoke) { $arguments = @('-Dbattleaudio.flowSmoke=true') + $arguments }
if ($AllLanguageFontSmoke) { $arguments = @('-Dbattleaudio.allLanguageFontSmoke=true') + $arguments }
if ($SettingsPerformanceMinimal) { $arguments = @('-Dbattleaudio.settingsPerformanceMinimal=true','-Dbattleaudio.settingsPerformanceSmoke=true') + $arguments }
if ($SettingsPerformanceSmoke) { $arguments = @('-Dbattleaudio.settingsPerformanceSmoke=true') + $arguments }
if ($GremlinSummonSmoke) { $arguments = @('-Dbattleaudio.gremlinSummonSmoke=true','-Dbattleaudio.freshEventsSmoke=true') + $arguments }
if ($LocalizationSmoke) { $arguments = @('-Dbattleaudio.localizationSmoke=true') + $arguments }
if ($Language) { $arguments = @('-Dbattleaudio.testLanguage="' + $Language + '"') + $arguments }
if ($FilterSmoke) { $arguments = @('-Dbattleaudio.filterSmoke=true') + $arguments }
if ($StyleSmoke) { $arguments = @('-Dbattleaudio.styleSmoke=true') + $arguments }
if ($NativeStyle) { $arguments = @('-Dbattleaudio.nativeStyle=true') + $arguments }
if ($ExpectName) { $arguments = @('-Dbattleaudio.expectName="' + $ExpectName + '"') + $arguments }
$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $output -WindowStyle Hidden -RedirectStandardOutput (Join-Path $output 'stdout.log') -RedirectStandardError (Join-Path $output 'stderr.log') -PassThru
$passed = Join-Path $output 'smoke-passed.txt'
if (!$process.WaitForExit(60000)) {
    $completed = Test-Path -LiteralPath $passed
    Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
    if (!$completed) { throw "Game smoke test timed out. Logs: $output" }
}
Get-Content -LiteralPath (Join-Path $output 'stderr.log')
if (!(Test-Path -LiteralPath $passed)) {
    Get-Content -LiteralPath (Join-Path $output 'stdout.log') -Tail 80
    throw "Game smoke test failed. Logs: $output"
}
Get-Content -LiteralPath $passed
Write-Output "Logs: $output"
