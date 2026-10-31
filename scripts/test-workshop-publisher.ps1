param(
    [Parameter(Mandatory=$true)][string]$GameDir,
    [Parameter(Mandatory=$true)][string]$WorkRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$helper = Join-Path (Split-Path $PSScriptRoot -Parent) '.agents\skills\battleaudio-workshop-publisher\scripts\prepare-workshop.ps1'
$tokens = $null; $parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) { throw ('Publisher helper syntax error: ' + $parseErrors[0].Message) }
$root = Join-Path ([IO.Path]::GetFullPath($WorkRoot)) ('publisher-test-' + [guid]::NewGuid().ToString('N'))
$bundle = Join-Path $root 'bundle'
$workspace = Join-Path $root 'workspace'
function Write-Bundle([string]$Version, [string]$Name, [string]$MediaPrefix, [byte[]]$SourceBytes, [byte[]]$CacheBytes) {
    $versionRoot = Join-Path $root ('bundle-' + $Version)
    New-Item -ItemType Directory -Path (Join-Path $versionRoot 'media') -Force | Out-Null
    $sourceName = $MediaPrefix + '.mp3'
    $cacheName = $MediaPrefix + '.wav'
    $sourcePath = Join-Path (Join-Path $versionRoot 'media') $sourceName
    $cachePath = Join-Path (Join-Path $versionRoot 'media') $cacheName
    [IO.File]::WriteAllBytes($sourcePath, $SourceBytes)
    [IO.File]::WriteAllBytes($cachePath, $CacheBytes)
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $cacheHash = (Get-FileHash -LiteralPath $cachePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        schemaVersion = 1; packageId = 'publisher-test-package-001'; workshopId = ''; name = $Name; version = $Version; enabled = $true
        rules = @(@{ id='test-rule'; candidates=@(@{ sourcePath=('media/' + $sourceName); cachePath=('media/' + $cacheName) }) })
        files = @(
            @{ path=('media/' + $sourceName); sha256=$sourceHash; bytes=$SourceBytes.Length },
            @{ path=('media/' + $cacheName); sha256=$cacheHash; bytes=$CacheBytes.Length }
        )
    }
    [IO.File]::WriteAllText((Join-Path $versionRoot 'music-pack.json'), (ConvertTo-Json -InputObject $manifest -Depth 16), ([Text.UTF8Encoding]::new($false)))
    $script:bundle = $versionRoot
}
function Invoke-Prepare([string]$PublishedId, [string]$Visibility) {
    $global:LASTEXITCODE = 0
    $parameters = @{ BundlePath=$bundle; GameDir=$GameDir; WorkspacePath=$workspace }
    if ($PublishedId) { $parameters.PublishedId = $PublishedId }
    if ($Visibility) { $parameters.Visibility = $Visibility }
    $output = & $helper @parameters 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Prepare-only helper failed: $($output | Out-String)" }
    return ($output | Out-String)
}
function Assert([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }

Write-Bundle '1.0.0' 'Publisher Test Pack' 'v1' ([byte[]](1,2,3,4)) ([byte[]](5,6,7))
$firstOutput = Invoke-Prepare
$firstConfig = Get-Content -LiteralPath (Join-Path $workspace 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$firstMarker = Get-Content -LiteralPath (Join-Path $workspace '.battleaudio-publisher.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert ($firstConfig.visibility -eq 'private') 'New items must default to private.'
Assert ($null -eq $firstConfig.PSObject.Properties['steamPublishedID']) 'Prepare-only must not invent a Workshop ID.'
Assert ($firstConfig.description.Contains('https://steamcommunity.com/sharedfiles/filedetails/?id=3830077500')) 'Generated description must link to the new Mod item.'
Assert ($firstMarker.packageId -eq 'publisher-test-package-001') 'Workspace ownership marker is missing package identity.'
Assert ($firstOutput.Contains('nothing was uploaded')) 'Prepare-only did not report that it avoided upload.'

$updateOutput = Invoke-Prepare '1234567890' 'friends'
$updateConfig = Get-Content -LiteralPath (Join-Path $workspace 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert ($updateOutput.Contains('Update existing item #1234567890')) 'A selected existing Workshop ID was not treated as an update.'
Assert ($updateConfig.steamPublishedID -eq '1234567890' -and $updateConfig.visibility -eq 'friends') 'The selected update ID or visibility was not staged.'

Write-Bundle '2.0.0' 'Renamed Publisher Pack' 'v2' ([byte[]](8,9,10,11,12)) ([byte[]](13,14,15,16))
$secondOutput = Invoke-Prepare
$secondConfig = Get-Content -LiteralPath (Join-Path $workspace 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$currentManifest = Get-Content -LiteralPath (Join-Path $workspace 'content\music-pack.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$historyContent = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'history') -Directory -Filter 'content-*' | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'media\v1.mp3') })
Assert ($currentManifest.version -eq '2.0.0') 'The updated package version was not staged.'
Assert ($secondOutput.Contains('Update existing item #1234567890')) 'The saved Workshop ID was not reused for the later package update.'
Assert ($secondConfig.title -eq 'BattleAudio - Renamed Publisher Pack') 'Generated title did not follow the updated package name.'
Assert ($secondConfig.description.Contains('Renamed Publisher Pack (2.0.0)')) 'Generated description did not follow the updated package version.'
Assert ($historyContent.Count -ge 1) 'Old uploader content was not preserved as a recovery snapshot.'
Assert (!(Test-Path -LiteralPath (Join-Path $workspace 'content\media\v1.mp3'))) 'A stale old-version file remained in the live upload content.'
Assert (Test-Path -LiteralPath (Join-Path $historyContent[0].FullName 'media\v1.mp3')) 'The prior version snapshot is missing its source file.'

$configPath = Join-Path $workspace 'config.json'
$customConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$customConfig.visibility = 'public'; $customConfig.title = 'My Reviewed Title'; $customConfig.description = 'My reviewed description.'
[IO.File]::WriteAllText($configPath, (ConvertTo-Json -InputObject $customConfig -Depth 16), ([Text.UTF8Encoding]::new($false)))
Write-Bundle '3.0.0' 'Third Publisher Pack' 'v3' ([byte[]](21,22,23,24,25,26)) ([byte[]](31,32,33))
[void](Invoke-Prepare)
$thirdConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert ($thirdConfig.visibility -eq 'public') 'Existing visibility was overwritten without user input.'
Assert ($thirdConfig.title -eq 'My Reviewed Title' -and $thirdConfig.description -eq 'My reviewed description.') 'Manually reviewed Workshop metadata was overwritten.'

# Capture the actual uploader launch against inert executables, never Steam.
$fakeGame = Join-Path $root 'fake-game'
$fakeJava = Join-Path $fakeGame 'jre\bin\java.exe'
New-Item -ItemType Directory -Path (Split-Path -Parent $fakeJava) -Force | Out-Null
[IO.File]::WriteAllBytes($fakeJava, [byte[]]@())
[IO.File]::WriteAllBytes((Join-Path $fakeGame 'mod-uploader.jar'), [byte[]]@())
$mockUploaderStarts = New-Object 'System.Collections.Generic.List[object]'
function Start-Process {
    [CmdletBinding()]
    param([string]$FilePath, [string]$ArgumentList, [string]$WorkingDirectory, [string]$WindowStyle, [switch]$PassThru, [switch]$Wait)
    [void]$mockUploaderStarts.Add([pscustomobject]@{ FilePath=$FilePath; ArgumentList=$ArgumentList; WorkingDirectory=$WorkingDirectory; WindowStyle=$WindowStyle; PassThru=$PassThru; Wait=$Wait })
    [pscustomobject]@{ ExitCode=0 }
}
[void](& $helper -BundlePath $bundle -GameDir $fakeGame -WorkspacePath $workspace -Upload)
Assert ($mockUploaderStarts.Count -eq 1) 'Uploader launch was not captured exactly once.'
$launch = $mockUploaderStarts[0]
Assert ($launch.FilePath -eq $fakeJava -and $launch.WorkingDirectory -eq $fakeGame) 'Uploader did not use the selected game runtime.'
$jarIndex = $launch.ArgumentList.IndexOf(' -jar ', [StringComparison]::Ordinal)
Assert ($jarIndex -gt 0) 'Uploader launch is missing its JVM options or JAR argument.'
$jvmArgs = ' ' + $launch.ArgumentList.Substring(0, $jarIndex) + ' '
Assert ($jvmArgs.Contains(' -Dfile.encoding=UTF-8 ')) 'Uploader must read configuration as UTF-8.'
Assert ($jvmArgs.Contains(' -Djava.awt.headless=true ')) 'Uploader must not spawn an unconfigured graphical process.'
Assert ($launch.WindowStyle -eq 'Hidden' -and $launch.PassThru -and $launch.Wait) 'Uploader process options changed unexpectedly.'

$managedSource = Join-Path $workspace 'content\media\v3.mp3'
[IO.File]::WriteAllBytes($managedSource, [byte[]](99,99,99))
$failed = $false
try { [void](Invoke-Prepare) } catch { $failed = $true }
Assert $failed 'A modified managed upload file was not rejected.'
$tamperedBytes = [IO.File]::ReadAllBytes($managedSource)
Assert ($tamperedBytes.Length -eq 3 -and $tamperedBytes[0] -eq 99) 'The helper changed a user-modified file while rejecting the update.'

[IO.File]::WriteAllBytes((Join-Path $bundle 'unlisted.txt'), [byte[]](1,1,1))
$failed = $false
try { [void](Invoke-Prepare) } catch { $failed = $true }
Assert $failed 'An unlisted export file was accepted.'
Assert (Test-Path -LiteralPath $managedSource) 'Rejecting an unlisted file changed the existing upload workspace.'
Write-Output 'PASS: Workshop publisher prepare-only, manifest/hash validation, full replacement, recovery snapshots, generated metadata refresh, visibility preservation, manual metadata, tamper refusal, UTF-8 launcher options, and no-upload behavior.'
Write-Output "Test workspace retained under: $root"
