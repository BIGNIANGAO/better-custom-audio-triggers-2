[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BundlePath,
    [string]$GameDir,
    [string]$WorkspacePath,
    [string]$Title,
    [string]$Description,
    [string]$Visibility,
    [string]$ChangeNote,
    [string[]]$Tags,
    [string]$PublishedId,
    [switch]$Upload
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Stop-Action([string]$Message) { throw $Message }
function Get-Full([string]$Path) { [IO.Path]::GetFullPath($Path) }
function Test-Within([string]$Root, [string]$Path) {
    $r = (Get-Full $Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $p = Get-Full $Path
    $p.StartsWith($r, [StringComparison]::OrdinalIgnoreCase)
}
function Assert-PlainPath([string]$Root, [string]$Relative, [switch]$MustExist) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative) -or $Relative -match '(^|[\\/])\.\.?([\\/]|$)' -or $Relative -match '^[A-Za-z]:') { Stop-Action "Unsafe package path: $Relative" }
    $normalized = $Relative.Replace('/', [IO.Path]::DirectorySeparatorChar).Replace('\', [IO.Path]::DirectorySeparatorChar)
    $full = Get-Full (Join-Path $Root $normalized)
    if (!(Test-Within $Root $full)) { Stop-Action "Package path escapes its root: $Relative" }
    $cursor = Get-Full $Root
    $rootItem = Get-Item -LiteralPath $cursor -Force
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Stop-Action "Linked roots are not allowed: $cursor" }
    $tail = $full.Substring($cursor.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar).Length).TrimStart([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    foreach ($part in $tail.Split([IO.Path]::DirectorySeparatorChar)) {
        if ($part -eq '') { continue }
        $cursor = Join-Path $cursor $part
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Stop-Action "Linked package paths are not allowed: $Relative" }
        } elseif ($MustExist) { Stop-Action "Package file is missing: $Relative" }
    }
    $full
}
function Get-Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-Property($Object, [string]$Name) { $property = $Object.PSObject.Properties[$Name]; if ($property) { $property.Value } else { $null } }
function Find-GameInstall([string]$Preferred) {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    if ($Preferred) { $candidates.Add((Get-Full $Preferred)) }
    $steamRoots = New-Object 'System.Collections.Generic.List[string]'
    try {
        $key = Get-ItemProperty -LiteralPath 'HKCU:\Software\Valve\Steam' -ErrorAction Stop
        foreach ($property in @('SteamPath','InstallPath')) { if ($key.$property -and (Test-Path -LiteralPath $key.$property)) { $steamRoots.Add((Get-Full $key.$property)) } }
    } catch { }
    foreach ($steam in @('C:\Program Files (x86)\Steam','C:\Program Files\Steam','D:\Steam','D:\SteamLibrary')) { if (Test-Path -LiteralPath $steam) { $steamRoots.Add((Get-Full $steam)) } }
    foreach ($steam in @($steamRoots | Select-Object -Unique)) {
        $candidates.Add((Join-Path $steam 'steamapps\common\SlayTheSpire'))
        $libraries = Join-Path $steam 'steamapps\libraryfolders.vdf'
        if (Test-Path -LiteralPath $libraries) {
            $text = Get-Content -LiteralPath $libraries -Raw -Encoding UTF8
            foreach ($match in [regex]::Matches($text, '"path"\s+"((?:\\.|[^"\\])*)"')) { $candidates.Add((Join-Path $match.Groups[1].Value.Replace('\\','\') 'steamapps\common\SlayTheSpire')) }
        }
    }
    foreach ($candidate in @($candidates | Select-Object -Unique)) { if (Test-Path -LiteralPath (Join-Path $candidate 'mod-uploader.jar') -PathType Leaf) { return (Get-Full $candidate) } }
    Stop-Action 'Could not find mod-uploader.jar. Pass -GameDir with the Slay the Spire install folder.'
}
function Get-Java([string]$Install) {
    $bundled = Join-Path $Install 'jre\bin\java.exe'
    if (Test-Path -LiteralPath $bundled -PathType Leaf) { return (Get-Full $bundled) }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { return $java.Source }
    Stop-Action 'No Java runtime was found. Use the Java runtime included with Slay the Spire or install Java.'
}
function Get-RelativeFiles([string]$Root) {
    $result = New-Object 'System.Collections.Generic.List[string]'
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force -Recurse) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Stop-Action "Linked files or folders are not allowed: $($item.FullName)" }
        if (!$item.PSIsContainer) { $result.Add($item.FullName.Substring((Get-Full $Root).TrimEnd('\').Length + 1).Replace('\','/')) }
    }
    @($result | Sort-Object -Unique)
}
function Write-JsonAtomic([string]$Path, $Object) {
    $temp = $Path + '.tmp-' + [guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($temp, ((ConvertTo-Json -InputObject $Object -Depth 32) + [Environment]::NewLine), ([Text.UTF8Encoding]::new($false)))
    if (Test-Path -LiteralPath $Path) { Move-Item -LiteralPath $temp -Destination $Path -Force } else { Move-Item -LiteralPath $temp -Destination $Path }
}

$bundleRoot = Get-Full $BundlePath
if (!(Test-Path -LiteralPath $bundleRoot -PathType Container)) { Stop-Action "Export folder not found: $bundleRoot" }
$manifestPath = Assert-PlainPath $bundleRoot 'music-pack.json' -MustExist
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.packageId -notmatch '^[A-Za-z0-9._-]{1,128}$' -or $manifest.packageId.Contains('..')) { Stop-Action 'The manifest version or package ID is invalid.' }
if ([string]::IsNullOrWhiteSpace($manifest.name) -or [string]::IsNullOrWhiteSpace($manifest.version) -or @($manifest.rules).Count -lt 1) { Stop-Action 'The music pack needs a name, version, and at least one rule.' }
if (@($manifest.files).Count -gt 4096) { Stop-Action 'The package has too many files.' }
$expected = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$records = New-Object 'System.Collections.Generic.List[object]'
$totalBytes = [long]0
foreach ($file in @($manifest.files)) {
    if (!$file -or $file.path -notmatch '^[^:]+$' -or $file.sha256 -notmatch '^[0-9a-fA-F]{64}$' -or $file.bytes -lt 0 -or $file.bytes -gt 536870912) { Stop-Action 'A media entry has an invalid path, size, or SHA-256.' }
    if (!$expected.Add($file.path)) { Stop-Action "Duplicate media path: $($file.path)" }
    $source = Assert-PlainPath $bundleRoot $file.path -MustExist
    $item = Get-Item -LiteralPath $source
    if ($item.PSIsContainer -or $item.Length -ne [long]$file.bytes -or (Get-Hash $source) -ne $file.sha256.ToLowerInvariant()) { Stop-Action "Media integrity check failed: $($file.path)" }
    $totalBytes += [long]$file.bytes
    $records.Add([pscustomobject]@{ path=$file.path; sha256=$file.sha256.ToLowerInvariant(); bytes=[long]$file.bytes })
}
if ($totalBytes -gt 2147483648) { Stop-Action 'The total package exceeds 2 GiB.' }
foreach ($rule in @($manifest.rules)) { foreach ($candidate in @($rule.candidates)) { foreach ($mediaPath in @($candidate.sourcePath,$candidate.cachePath)) { if ($null -ne $mediaPath -and "$mediaPath" -ne '' -and !$expected.Contains("$mediaPath")) { Stop-Action "Rule media is not listed: $mediaPath" } } } }
$actualBundleFiles = Get-RelativeFiles $bundleRoot
$expectedBundle = @($expected | ForEach-Object { $_ }) + @('music-pack.json')
if (Compare-Object ($actualBundleFiles | Sort-Object) ($expectedBundle | Sort-Object) -CaseSensitive) { Stop-Action 'The export contains unlisted files or is missing listed files. Nothing was staged.' }

$game = Find-GameInstall $GameDir
$uploader = Join-Path $game 'mod-uploader.jar'
$java = Get-Java $game
if (!$WorkspacePath) { $WorkspacePath = Join-Path (Join-Path $game 'BetterCustomAudioTriggers\workshop-exports\publisher-workspaces') $manifest.packageId }
$workspace = Get-Full $WorkspacePath
if (Test-Path -LiteralPath $workspace) {
    $workspaceItem = Get-Item -LiteralPath $workspace -Force
    if (!$workspaceItem.PSIsContainer -or ($workspaceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Stop-Action 'The uploader workspace is not a normal folder.' }
} else {
    New-Item -ItemType Directory -Path (Split-Path -Parent $workspace) -Force | Out-Null
    New-Item -ItemType Directory -Path $workspace | Out-Null
    try {
        $contentDirectory = Join-Path $workspace 'content'
        New-Item -ItemType Directory -Path $contentDirectory | Out-Null
        $defaultImage = Join-Path $workspace 'image.jpg'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($uploader)
        try {
            $imageEntry = $archive.GetEntry('image.jpg')
            if ($null -eq $imageEntry) { Stop-Action 'The game uploader archive has no default preview image.' }
            $input = $imageEntry.Open()
            $output = [IO.File]::Create($defaultImage)
            try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        } finally { $archive.Dispose() }
        $defaultConfig = [ordered]@{ title=''; description=''; visibility='private'; changeNote=''; tags=@() }
        Write-JsonAtomic (Join-Path $workspace 'config.json') $defaultConfig
        [IO.File]::WriteAllText((Join-Path $workspace 'README.md'), '# Steam Workshop music pack workspace' + [Environment]::NewLine, ([Text.UTF8Encoding]::new($false)))
    } catch {
        $workspaceFull = Get-Full $workspace
        if ((Test-Path -LiteralPath $workspaceFull -PathType Container) -and $workspaceFull.StartsWith((Get-Full (Split-Path -Parent $workspaceFull)).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $workspaceFull -Recurse -Force
        }
        throw
    }
}
$markerPath = Assert-PlainPath $workspace '.battleaudio-publisher.json'
$configPath = Assert-PlainPath $workspace 'config.json' -MustExist
$content = Assert-PlainPath $workspace 'content' -MustExist
$owner = $null
$imagePath = Assert-PlainPath $workspace 'image.jpg' -MustExist
if ((Get-Item -LiteralPath $imagePath).PSIsContainer) { Stop-Action 'The uploader preview image is not a file.' }
if (!(Test-Path -LiteralPath $content -PathType Container)) { Stop-Action 'The uploader workspace is incomplete; it was left untouched.' }
if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
    $owner = Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($owner.packageId -ne $manifest.packageId) { Stop-Action 'This uploader workspace belongs to another package.' }
    $oldFiles = @($owner.files)
    $actualOld = Get-RelativeFiles $content
    $oldExpected = @($oldFiles | ForEach-Object { $_.path })
    if (Compare-Object ($actualOld | Sort-Object) ($oldExpected | Sort-Object) -CaseSensitive) { Stop-Action 'Managed uploader content has unexpected or missing files. It was not changed.' }
    foreach ($old in $oldFiles) { $oldPath = Assert-PlainPath $content $old.path -MustExist; if ((Get-Hash $oldPath) -ne $old.sha256) { Stop-Action "A managed uploader file was edited outside the game; it was preserved: $($old.path)" } }
} else {
    if ((Get-ChildItem -LiteralPath $content -Force | Measure-Object).Count -gt 0) { Stop-Action 'The workspace has unowned content and was left untouched.' }
    $unknown = Get-ChildItem -LiteralPath $workspace -Force | Where-Object { $_.Name -notin @('content','config.json','image.jpg','README.md') }
    if ($unknown) { Stop-Action 'This is not a fresh game-created uploader workspace; it was left untouched.' }
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$previousGeneratedTitle = if ($owner) { Get-Property $owner 'generatedTitle' } else { $null }
$previousGeneratedDescription = if ($owner) { Get-Property $owner 'generatedDescription' } else { $null }
$existingId = Get-Property $config 'steamPublishedID'
if ($PublishedId) {
    if ($PublishedId -notmatch '^[0-9]{1,20}$') { Stop-Action 'PublishedId must be numeric.' }
    if ($existingId -and "$existingId" -ne $PublishedId) { Stop-Action 'The requested Workshop ID conflicts with the workspace ID.' }
    if (!$existingId) { $config | Add-Member -NotePropertyName steamPublishedID -NotePropertyValue $PublishedId -Force }
}
$generatedTitle = "BattleAudio - $($manifest.name)"
$trackedGeneratedTitle = $null
if ($Title) { $config.title = $Title }
elseif ([string]::IsNullOrWhiteSpace($config.title) -or ($previousGeneratedTitle -and $config.title -eq $previousGeneratedTitle)) { $config.title = $generatedTitle; $trackedGeneratedTitle = $generatedTitle }
else { $trackedGeneratedTitle = $previousGeneratedTitle }
if ($config.title.Length -gt 128) { Stop-Action 'Workshop title exceeds 128 characters.' }
$link = 'https://steamcommunity.com/sharedfiles/filedetails/?id=3830077500'
$generatedDescription = @"
$($manifest.name) ($($manifest.version))

An independent music pack for BattleAudio. Requires the BattleAudio mod:
$link

Rules and audio are packaged in this item and are loaded by BattleAudio.
"@
$trackedGeneratedDescription = $null
if ($Description) { $config.description = $Description }
elseif ([string]::IsNullOrWhiteSpace($config.description) -or ($previousGeneratedDescription -and $config.description -eq $previousGeneratedDescription)) { $config.description = $generatedDescription; $trackedGeneratedDescription = $generatedDescription }
else { $trackedGeneratedDescription = $previousGeneratedDescription }
if ($Visibility) { $config.visibility = $Visibility } elseif ([string]::IsNullOrWhiteSpace($config.visibility)) { $config.visibility = 'private' }
if ($config.visibility -notin @('private','friends','friendsonly','public')) { Stop-Action 'Visibility must be private, friends, friendsonly, or public.' }
if ($ChangeNote) { $config.changeNote = $ChangeNote } else { $config.changeNote = "BattleAudio package version $($manifest.version)" }
if ($Tags) { $config.tags = @($Tags) } elseif (!$config.tags -or @($config.tags).Count -eq 0) { $config.tags = @('Mod') }

$stage = Join-Path $workspace ('.battleaudio-content-stage-' + [guid]::NewGuid().ToString('N'))
$backup = $null
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    $stageFiles = New-Object 'System.Collections.Generic.List[object]'
    $toCopy = @([pscustomobject]@{ path='music-pack.json'; source=$manifestPath })
    foreach ($record in $records) { $toCopy += [pscustomobject]@{ path=$record.path; source=(Assert-PlainPath $bundleRoot $record.path -MustExist) } }
    foreach ($entry in $toCopy) {
        $target = Assert-PlainPath $stage $entry.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $entry.source -Destination $target
        $stageFiles.Add([pscustomobject]@{ path=$entry.path; sha256=(Get-Hash $target); bytes=[long](Get-Item -LiteralPath $target).Length })
    }
    $newOwner = [pscustomobject]@{ owner='BattleAudio'; schemaVersion=1; packageId=$manifest.packageId; packageVersion=$manifest.version; generatedTitle=$trackedGeneratedTitle; generatedDescription=$trackedGeneratedDescription; files=$stageFiles.ToArray() }
    $history = Join-Path $workspace 'history'
    New-Item -ItemType Directory -Path $history -Force | Out-Null
    if (Test-Path -LiteralPath $content) {
        $backup = Join-Path $history ('content-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N'))
        Move-Item -LiteralPath $content -Destination $backup
    }
    try { Move-Item -LiteralPath $stage -Destination $content }
    catch { if ($backup -and !(Test-Path -LiteralPath $content)) { Move-Item -LiteralPath $backup -Destination $content }; throw }
    if (Test-Path -LiteralPath $markerPath) { Copy-Item -LiteralPath $markerPath -Destination (Join-Path $history ('owner-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N') + '.json')) }
    Copy-Item -LiteralPath $configPath -Destination (Join-Path $history ('config-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N') + '.json'))
    Write-JsonAtomic $markerPath $newOwner
    Write-JsonAtomic $configPath $config
} catch {
    if (Test-Path -LiteralPath $stage) { $stageFull=Get-Full $stage; if ($stageFull.StartsWith((Get-Full $workspace).TrimEnd('\') + '\.battleaudio-content-stage-', [StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $stageFull -Recurse -Force } }
    throw
}
$mode = if ((Get-Property $config 'steamPublishedID')) { 'Update existing item #' + (Get-Property $config 'steamPublishedID') } else { 'Create a new item' }
[pscustomobject]@{ Result=if($Upload){'Prepared; upload will start now.'}else{'Prepared only; nothing was uploaded.'}; Action=$mode; Package=$manifest.name; Version=$manifest.version; Title=$config.title; Visibility=$config.visibility; Files=$stageFiles.Count; MediaBytes=$totalBytes; Workspace=$workspace; ModLink=$link; Description=$config.description } | Format-List | Out-String | Write-Output
if (!$Upload) { Write-Output 'No Workshop request was made. Review the summary, then use -Upload only after explicit approval for this exact action.'; return }
$arguments = '-jar "{0}" upload -w "{1}"' -f $uploader.Replace('"','\"'), $workspace.Replace('"','\"')
$arguments = '-Dfile.encoding=UTF-8 -Djava.awt.headless=true ' + $arguments
$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $game -WindowStyle Hidden -PassThru -Wait
Write-Output "Uploader process finished with exit code $($process.ExitCode). Verify the Workshop page before reporting success."
$configAfter = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (Get-Property $configAfter 'steamPublishedID') { Write-Output "Saved Workshop ID: $((Get-Property $configAfter 'steamPublishedID')). Reuse this ID if retrying; do not create a duplicate." }
