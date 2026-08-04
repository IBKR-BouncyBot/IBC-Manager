[CmdletBinding()]
param(
    [ValidateSet('Run', 'Build', 'Test', 'Package', 'Validate')]
    [string] $Mode = 'Run',

    [string] $EnvironmentFile,

    [switch] $SelfTest
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$MinimumJavaMajor = 17
$WixPackageId = 'WiXToolset.WiXToolset'
$WixMinimumVersion = [Version] '3.0.0'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Write-Status {
    param([Parameter(Mandatory = $true)][string] $Message)
    Write-Host ('[IBC Manager] ' + $Message)
}

function Join-OptionalPath {
    param([string] $Parent, [Parameter(Mandatory = $true)][string] $Child)
    if ([string]::IsNullOrWhiteSpace($Parent)) {
        return $null
    }
    return Join-Path $Parent $Child
}

function Get-ManagedToolsRoot {
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        return Join-Path $env:LOCALAPPDATA 'IBCManager\tools'
    }
    return Join-Path $ProjectRoot '.tools'
}

$ManagedToolsRoot = Get-ManagedToolsRoot
$ManagedJdkHome = Join-Path $ManagedToolsRoot 'microsoft-jdk-17'

function Get-JavaMajorFromVersionText {
    param([Parameter(Mandatory = $true)][string] $Text)

    $match = [Regex]::Match($Text, '(?i)version\s+"([^"]+)"')
    if (-not $match.Success) {
        $match = [Regex]::Match($Text, '(?im)^\s*(?:openjdk|java)\s+([0-9][^\s]*)')
    }
    if (-not $match.Success) {
        return $null
    }

    $versionText = $match.Groups[1].Value
    if ($versionText.StartsWith('1.')) {
        $legacy = [Regex]::Match($versionText, '^1\.([0-9]+)')
        if ($legacy.Success) {
            return [int] $legacy.Groups[1].Value
        }
        return $null
    }

    $modern = [Regex]::Match($versionText, '^([0-9]+)')
    if ($modern.Success) {
        return [int] $modern.Groups[1].Value
    }
    return $null
}

function ConvertTo-WindowsCommandLineArgument {
    param([AllowEmptyString()][Parameter(Mandatory = $true)][string] $Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }

    $builder = New-Object Text.StringBuilder
    [void] $builder.Append('"')
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            [void] $builder.Append((('\' * (($backslashes * 2) + 1)) -join ''))
            [void] $builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void] $builder.Append((('\' * $backslashes) -join ''))
            $backslashes = 0
        }
        [void] $builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void] $builder.Append((('\' * ($backslashes * 2)) -join ''))
    }
    [void] $builder.Append('"')
    return $builder.ToString()
}

function Invoke-ToolForText {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [hashtable] $Environment = @{},
        [int] $TimeoutSeconds = 30
    )

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = (($Arguments | ForEach-Object {
        ConvertTo-WindowsCommandLineArgument ([string] $_)
    }) -join ' ')
    foreach ($entry in $Environment.GetEnumerator()) {
        $startInfo.EnvironmentVariables[[string] $entry.Key] = [string] $entry.Value
    }

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Could not start $FilePath"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            throw "Timed out after $TimeoutSeconds seconds while running $FilePath"
        }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Text = ($stdout + [Environment]::NewLine + $stderr).Trim()
        }
    } finally {
        $process.Dispose()
    }
}

function Add-CandidateHome {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]] $List,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.HashSet[string]] $Seen,

        [string] $CandidateHome
    )

    if ([string]::IsNullOrWhiteSpace($CandidateHome)) {
        return
    }
    try {
        $expanded = [Environment]::ExpandEnvironmentVariables($CandidateHome.Trim().Trim('"'))
        $full = [IO.Path]::GetFullPath($expanded)
    } catch {
        return
    }
    if ($Seen.Add($full)) {
        $List.Add($full)
    }
}

function Get-JavaCandidateHomes {
    $homes = New-Object 'System.Collections.Generic.List[string]'
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)

    Add-CandidateHome $homes $seen $env:IBC_MANAGER_JAVA_HOME
    Add-CandidateHome $homes $seen $ManagedJdkHome
    Add-CandidateHome $homes $seen $env:JAVA_HOME

    foreach ($pathEntry in @($env:PATH -split ';')) {
        if ([string]::IsNullOrWhiteSpace($pathEntry)) { continue }
        $cleanEntry = $pathEntry.Trim().Trim('"')
        try {
            $javaOnPath = Join-Path $cleanEntry 'java.exe'
            $javacOnPath = Join-Path $cleanEntry 'javac.exe'
        } catch {
            continue
        }
        if ((Test-Path -LiteralPath $javaOnPath -PathType Leaf) -or
                (Test-Path -LiteralPath $javacOnPath -PathType Leaf)) {
            Add-CandidateHome $homes $seen (Split-Path -Parent $cleanEntry)
        }
    }

    foreach ($commandName in @('java.exe', 'javac.exe')) {
        $commands = @(Get-Command $commandName -All -ErrorAction SilentlyContinue)
        foreach ($command in $commands) {
            if ($null -ne $command -and -not [string]::IsNullOrWhiteSpace($command.Source)) {
                Add-CandidateHome $homes $seen (Split-Path -Parent (Split-Path -Parent $command.Source))
            }
        }
    }

    $roots = @(
        (Join-OptionalPath $env:ProgramFiles 'Microsoft'),
        (Join-OptionalPath $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-OptionalPath $env:ProgramFiles 'Java'),
        (Join-OptionalPath $env:ProgramFiles 'Amazon Corretto'),
        (Join-OptionalPath $env:ProgramFiles 'BellSoft'),
        (Join-OptionalPath $env:ProgramFiles 'Zulu'),
        (Join-OptionalPath $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
    )
    foreach ($root in $roots) {
        if ([string]::IsNullOrWhiteSpace($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Add-CandidateHome $homes $seen $_.FullName }
    }

    return $homes
}

function Get-JavaInfo {
    param(
        [Parameter(Mandatory = $true)][string] $JavaHome,
        [bool] $RequireDevelopmentTools,
        [bool] $RequireJpackage
    )

    $java = Join-Path $JavaHome 'bin\java.exe'
    $javaw = Join-Path $JavaHome 'bin\javaw.exe'
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $jar = Join-Path $JavaHome 'bin\jar.exe'
    $jpackage = Join-Path $JavaHome 'bin\jpackage.exe'

    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javaw -PathType Leaf)) {
        return $null
    }
    if ($RequireDevelopmentTools -and
            (-not (Test-Path -LiteralPath $javac -PathType Leaf) -or
             -not (Test-Path -LiteralPath $jar -PathType Leaf))) {
        return $null
    }
    if ($RequireJpackage -and -not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
        return $null
    }

    try {
        $result = Invoke-ToolForText $java @('-version')
        if ($result.ExitCode -ne 0) {
            return $null
        }
        $major = Get-JavaMajorFromVersionText $result.Text
        if ($null -eq $major -or $major -lt $MinimumJavaMajor) {
            return $null
        }
        return [PSCustomObject]@{
            Home = $JavaHome
            Major = $major
            Java = $java
            Javaw = $javaw
            Javac = $javac
            Jar = $jar
            Jpackage = $jpackage
            VersionText = $result.Text.Split([Environment]::NewLine)[0]
        }
    } catch {
        return $null
    }
}

function Find-CompatibleJava {
    param([bool] $RequireDevelopmentTools, [bool] $RequireJpackage)

    foreach ($candidateHome in Get-JavaCandidateHomes) {
        $info = Get-JavaInfo $candidateHome $RequireDevelopmentTools $RequireJpackage
        if ($null -ne $info) {
            return $info
        }
    }
    return $null
}

function Get-WixVersionFromText {
    param([Parameter(Mandatory = $true)][string] $Text)
    $match = [Regex]::Match($Text, '(?im)(?:Windows Installer XML Toolset Compiler version|version)\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)')
    if (-not $match.Success) {
        return $null
    }
    try { return [Version] $match.Groups[1].Value } catch { return $null }
}

function Get-WixCandidateDirectories {
    $directories = New-Object 'System.Collections.Generic.List[string]'
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($directory in @(
        $env:IBC_MANAGER_WIX_BIN,
        $env:WIX,
        (Join-OptionalPath $env:WIX 'bin'),
        (Join-OptionalPath ${env:ProgramFiles(x86)} 'WiX Toolset v3.14\bin'),
        (Join-OptionalPath ${env:ProgramFiles(x86)} 'WiX Toolset v3.11\bin'),
        (Join-OptionalPath $env:ProgramFiles 'WiX Toolset v3.14\bin'),
        (Join-OptionalPath $env:ProgramFiles 'WiX Toolset v3.11\bin')
    )) {
        if ([string]::IsNullOrWhiteSpace($directory)) { continue }
        try {
            $expanded = [Environment]::ExpandEnvironmentVariables($directory.Trim().Trim('"'))
            $full = [IO.Path]::GetFullPath($expanded)
        } catch {
            continue
        }
        if ($seen.Add($full)) { $directories.Add($full) }
    }

    foreach ($command in @(Get-Command candle.exe -All -ErrorAction SilentlyContinue)) {
        if ($null -ne $command -and -not [string]::IsNullOrWhiteSpace($command.Source)) {
            $directory = Split-Path -Parent $command.Source
            if ($seen.Add($directory)) { $directories.Add($directory) }
        }
    }
    return $directories
}

function Get-WixInfo {
    foreach ($directory in Get-WixCandidateDirectories) {
        $candle = Join-Path $directory 'candle.exe'
        $light = Join-Path $directory 'light.exe'
        if (-not (Test-Path -LiteralPath $candle -PathType Leaf) -or
                -not (Test-Path -LiteralPath $light -PathType Leaf)) {
            continue
        }
        try {
            $result = Invoke-ToolForText $candle @('-?')
            $version = Get-WixVersionFromText $result.Text
            if ($null -ne $version -and $version -ge $WixMinimumVersion -and $version.Major -eq 3) {
                return [PSCustomObject]@{
                    Bin = $directory
                    Candle = $candle
                    Light = $light
                    Version = $version
                }
            }
        } catch { }
    }
    return $null
}

function Get-WindowsArchitectureName {
    $architecture = $env:PROCESSOR_ARCHITEW6432
    if ([string]::IsNullOrWhiteSpace($architecture)) {
        $architecture = $env:PROCESSOR_ARCHITECTURE
    }
    switch -Regex ($architecture) {
        '^(AMD64|x86_64)$' { return 'x64' }
        '^(ARM64|AARCH64)$' { return 'aarch64' }
        default { throw "Unsupported Windows architecture: $architecture. IBC Manager requires x64 or ARM64." }
    }
}

function Invoke-Download {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)][string] $OutFile,
        [int] $TimeoutSeconds = 900
    )

    $oldProtocol = [Net.ServicePointManager]::SecurityProtocol
    $oldProgressPreference = $ProgressPreference
    try {
        [Net.ServicePointManager]::SecurityProtocol = $oldProtocol -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        $headers = @{
            'Cache-Control' = 'no-cache'
            'Pragma' = 'no-cache'
            'User-Agent' = 'IBC-Manager-Prerequisite-Bootstrap/1.0.19'
        }
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $OutFile -TimeoutSec $TimeoutSeconds `
            -Headers $headers | Out-Null
    } finally {
        $ProgressPreference = $oldProgressPreference
        [Net.ServicePointManager]::SecurityProtocol = $oldProtocol
    }
}

function Get-ChecksumFromText {
    param(
        [Parameter(Mandatory = $true)][string] $Text,
        [Parameter(Mandatory = $true)][int] $HexLength
    )
    $match = [Regex]::Match($Text, '(?i)(?<![0-9a-f])[0-9a-f]{' + $HexLength + '}(?![0-9a-f])')
    if (-not $match.Success) {
        throw "The downloaded checksum file did not contain a $HexLength-character hexadecimal checksum."
    }
    return $match.Value.ToLowerInvariant()
}

function Assert-FileChecksum {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][ValidateSet('SHA256', 'SHA512')][string] $Algorithm,
        [Parameter(Mandatory = $true)][string] $Expected
    )
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "$Algorithm verification failed for $Path. Expected $Expected but received $actual."
    }
}

function Complete-ManagedDirectoryInstall {
    param(
        [Parameter(Mandatory = $true)][string] $StagedDirectory,
        [Parameter(Mandatory = $true)][string] $Destination
    )

    $backup = $Destination + '.old-' + [Guid]::NewGuid().ToString('N')
    if ((Test-Path -LiteralPath $Destination) -and
            -not (Test-Path -LiteralPath $Destination -PathType Container)) {
        throw "Managed tool destination exists but is not a directory: $Destination"
    }
    $hadExisting = Test-Path -LiteralPath $Destination -PathType Container
    if ($hadExisting) {
        Move-Item -LiteralPath $Destination -Destination $backup
    }

    try {
        Move-Item -LiteralPath $StagedDirectory -Destination $Destination
    } catch {
        if (Test-Path -LiteralPath $Destination) {
            Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
        }
        if ($hadExisting -and (Test-Path -LiteralPath $backup)) {
            Move-Item -LiteralPath $backup -Destination $Destination -ErrorAction SilentlyContinue
        }
        throw
    }

    # Failure to delete an obsolete backup must not roll back a verified and
    # successfully activated replacement. Leave the backup for manual cleanup.
    if ($hadExisting -and (Test-Path -LiteralPath $backup)) {
        try {
            Remove-Item -LiteralPath $backup -Recurse -Force
        } catch {
            Write-Warning "The new managed tool is active, but its obsolete backup could not be removed: $backup"
        }
    }
}

function Install-ManagedJdk {
    $architecture = Get-WindowsArchitectureName
    $url = "https://aka.ms/download-jdk/microsoft-jdk-17-windows-$architecture.zip"
    $checksumUrl = $url + '.sha256sum.txt'
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('IBCManager-JDK-' + [Guid]::NewGuid().ToString('N'))
    $archive = Join-Path $temporaryRoot 'jdk.zip'
    $checksumFile = Join-Path $temporaryRoot 'jdk.sha256'
    $extract = Join-Path $temporaryRoot 'extract'
    $stagedDestination = Join-Path $ManagedToolsRoot ('microsoft-jdk-17.new-' + [Guid]::NewGuid().ToString('N'))

    try {
        New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
        New-Item -ItemType Directory -Path $ManagedToolsRoot -Force | Out-Null
        Write-Status "Downloading Microsoft Build of OpenJDK 17 for Windows $architecture..."
        Invoke-Download $url $archive
        Invoke-Download $checksumUrl $checksumFile
        $expected = Get-ChecksumFromText (Get-Content -LiteralPath $checksumFile -Raw) 64
        Assert-FileChecksum $archive 'SHA256' $expected

        Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
        $javaFiles = @(Get-ChildItem -LiteralPath $extract -Filter java.exe -File -Recurse |
            Where-Object { $_.FullName -match '[\\/]bin[\\/]java\.exe$' })
        if ($javaFiles.Count -ne 1) {
            throw "Expected exactly one Java runtime in the Microsoft JDK archive; found $($javaFiles.Count)."
        }
        $jdkRoot = Split-Path -Parent (Split-Path -Parent $javaFiles[0].FullName)
        Move-Item -LiteralPath $jdkRoot -Destination $stagedDestination
        $verified = Get-JavaInfo $stagedDestination $true $true
        if ($null -eq $verified -or $verified.Major -ne 17) {
            throw 'The downloaded Microsoft JDK did not pass the Java 17/JDK tool verification.'
        }

        Complete-ManagedDirectoryInstall $stagedDestination $ManagedJdkHome
        Write-Status "Installed Microsoft OpenJDK 17 under $ManagedJdkHome"
    } finally {
        if (Test-Path -LiteralPath $stagedDestination) {
            Remove-Item -LiteralPath $stagedDestination -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $temporaryRoot) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Install-WixWithWinget {
    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $winget -or [string]::IsNullOrWhiteSpace($winget.Source)) {
        throw 'Windows Package Manager (winget) is required to install WiX automatically. Install Microsoft App Installer or WiX Toolset 3.14.1 manually, then rerun package-windows.bat.'
    }

    Write-Status 'Installing WiX Toolset 3.14.1 through Windows Package Manager. A Windows elevation prompt may appear.'
    $result = Invoke-ToolForText $winget.Source @(
        'install', '--id', $WixPackageId, '--exact', '--source', 'winget',
        '--accept-package-agreements', '--accept-source-agreements', '--silent', '--disable-interactivity'
    ) -TimeoutSeconds 300
    if ($result.ExitCode -ne 0) {
        throw "winget could not install $WixPackageId. Exit code $($result.ExitCode). $($result.Text)"
    }
}

function Get-JavaRequirementDescription {
    param([bool] $RequireDevelopmentTools, [bool] $RequireJpackage)
    if ($RequireJpackage) {
        return 'Java 17 or newer JDK with javac, jar, and jpackage'
    }
    if ($RequireDevelopmentTools) {
        return 'Java 17 or newer JDK with javac and jar'
    }
    return 'Java 17 or newer runtime'
}

function Confirm-PrerequisiteInstallation {
    param(
        [Parameter(Mandatory = $true)][string[]] $Missing,
        [Parameter(Mandatory = $true)][string] $JavaRequirement
    )

    Write-Host ''
    Write-Host 'IBC Manager cannot continue because these prerequisites are missing or incompatible:'
    foreach ($item in $Missing) {
        Write-Host ('  - ' + $item)
    }
    Write-Host ''
    Write-Host 'With your permission, this script will:'
    if ($Missing -contains $JavaRequirement) {
        Write-Host "  - download Microsoft OpenJDK 17, verify its SHA-256 checksum, and install it only under $ManagedJdkHome"
        Write-Host '    Existing Java 8 installations and the system PATH will not be removed or changed.'
    }
    if ($Missing -contains 'WiX Toolset 3.x') {
        Write-Host "  - ask Windows Package Manager to install $WixPackageId; Windows may request administrator approval"
    }
    Write-Host ''

    $answer = Read-Host 'Proceed with prerequisite installation? [Y/N]'
    return $answer -match '^(?i:y|yes)$'
}

function Escape-BatchValue {
    param([Parameter(Mandatory = $true)][string] $Value)
    if ($Value.IndexOf([char] 0) -ge 0 -or $Value.Contains("`r") -or
            $Value.Contains("`n") -or $Value.Contains('"')) {
        throw 'A prerequisite path contains a character that cannot be represented safely in a Windows batch environment file.'
    }
    return $Value.Replace('^', '^^').Replace('%', '%%')
}

function Get-ConsoleBatchEncoding {
    $codePage = [Console]::OutputEncoding.CodePage
    if ($codePage -eq 65001) {
        return [Text.UTF8Encoding]::new($false, $true)
    }
    return [Text.Encoding]::GetEncoding(
        $codePage,
        [Text.EncoderFallback]::ExceptionFallback,
        [Text.DecoderFallback]::ExceptionFallback)
}

function Write-EnvironmentBatchFile {
    param(
        [Parameter(Mandatory = $true)] $JavaInfo,
        $WixInfo
    )

    if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
        throw 'EnvironmentFile is required unless -SelfTest is used.'
    }
    $fullPath = [IO.Path]::GetFullPath($EnvironmentFile)
    $parent = Split-Path -Parent $fullPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    $pathParts = New-Object 'System.Collections.Generic.List[string]'
    $pathParts.Add((Join-Path $JavaInfo.Home 'bin'))
    if ($null -ne $WixInfo) { $pathParts.Add($WixInfo.Bin) }

    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add('@echo off')
    $lines.Add('set "JAVA_HOME=' + (Escape-BatchValue $JavaInfo.Home) + '"')
    $lines.Add('set "IBC_MANAGER_JAVA_EXE=' + (Escape-BatchValue $JavaInfo.Java) + '"')
    $lines.Add('set "IBC_MANAGER_JAVAW_EXE=' + (Escape-BatchValue $JavaInfo.Javaw) + '"')
    $lines.Add('set "IBC_MANAGER_JAVAC_EXE=' + (Escape-BatchValue $JavaInfo.Javac) + '"')
    $lines.Add('set "IBC_MANAGER_JAR_EXE=' + (Escape-BatchValue $JavaInfo.Jar) + '"')
    $lines.Add('set "IBC_MANAGER_JPACKAGE_EXE=' + (Escape-BatchValue $JavaInfo.Jpackage) + '"')
    if ($null -ne $WixInfo) {
        $lines.Add('set "IBC_MANAGER_WIX_BIN=' + (Escape-BatchValue $WixInfo.Bin) + '"')
    }
    $escapedPath = ($pathParts | ForEach-Object { Escape-BatchValue $_ }) -join ';'
    $lines.Add('set "PATH=' + $escapedPath + ';%PATH%"')

    try {
        [IO.File]::WriteAllLines($fullPath, $lines, (Get-ConsoleBatchEncoding))
    } catch [Text.EncoderFallbackException] {
        throw ('A prerequisite path cannot be represented by the active Windows console code page. ' +
            'Run chcp 65001 in this Command Prompt and try again, or install Java in an ASCII-only path.')
    }
}

function Invoke-SelfTest {
    param([switch] $Quiet)

    # HOME is a read-only automatic variable in Windows PowerShell, and variable
    # names are case-insensitive. Reject a direct use of that protected name
    # before discovery so a future parameter, loop variable, or assignment
    # cannot recreate the launcher-blocking collision fixed in version 1.0.4.
    $bootstrapSource = Get-Content -LiteralPath $PSCommandPath -Raw
    if ([Regex]::IsMatch($bootstrapSource, '(?i)\$(?:\{)?home(?:\})?(?![A-Za-z0-9_])')) {
        throw 'Bootstrap source uses the protected HOME automatic-variable name.'
    }

    $javaCases = @{
        'java version "1.8.0_401"' = 8
        'openjdk version "17.0.12" 2024-07-16' = 17
        'openjdk version "21.0.5" 2024-10-15 LTS' = 21
        'openjdk 25-ea 2025-09-16' = 25
    }
    foreach ($entry in $javaCases.GetEnumerator()) {
        $actual = Get-JavaMajorFromVersionText $entry.Key
        if ($actual -ne $entry.Value) {
            throw "Java version parser self-test failed for '$($entry.Key)': expected $($entry.Value), received $actual"
        }
    }
    if ((Get-WixVersionFromText 'Windows Installer XML Toolset Compiler version 3.14.1.8722') -ne [Version] '3.14.1') {
        throw 'WiX version parser self-test failed.'
    }
    if ((ConvertTo-WindowsCommandLineArgument '') -ne '""') {
        throw 'Empty Windows command-line argument self-test failed.'
    }
    if ((ConvertTo-WindowsCommandLineArgument 'plain') -ne 'plain') {
        throw 'Plain Windows command-line argument self-test failed.'
    }
    if ((ConvertTo-WindowsCommandLineArgument 'C:\Program Files\Java') -ne '"C:\Program Files\Java"') {
        throw 'Spaced Windows command-line argument self-test failed.'
    }
    $escaped = Escape-BatchValue 'C:\Tools\100%^safe'
    if ($escaped -ne 'C:\Tools\100%%^^safe') {
        throw "Batch escaping self-test failed: $escaped"
    }
    if ((Get-JavaRequirementDescription $false $false) -ne 'Java 17 or newer runtime') {
        throw 'Runtime prerequisite description self-test failed.'
    }
    if ((Get-JavaRequirementDescription $true $true) -notmatch 'jpackage') {
        throw 'Packaging prerequisite description self-test failed.'
    }

    # Windows PowerShell rejects empty collections for mandatory parameters unless
    # AllowEmptyCollection is declared. These collections are intentionally empty
    # on the first candidate-discovery call, so exercise the actual binding path.
    $candidateHomes = New-Object 'System.Collections.Generic.List[string]'
    $candidateSeen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    Add-CandidateHome -List $candidateHomes -Seen $candidateSeen -CandidateHome $ProjectRoot
    Add-CandidateHome -List $candidateHomes -Seen $candidateSeen -CandidateHome $ProjectRoot
    if ($candidateHomes.Count -ne 1 -or $candidateSeen.Count -ne 1) {
        throw 'Empty candidate-collection binding or duplicate suppression self-test failed.'
    }

    if (-not $Quiet) {
        Write-Status 'Prerequisite bootstrap self-test passed.'
    }
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This prerequisite bootstrap is intended for Windows only.'
}
if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
    throw 'EnvironmentFile is required.'
}

# Execute deterministic bootstrap invariants on every entry point, not only
# validate-windows.bat, so script-level regressions fail before tool discovery.
Invoke-SelfTest -Quiet

# Build, test, validation, and packaging use the JDK-native BuildProject.java driver.
# Apache Ant is intentionally not a prerequisite.
$requiresBuildTools = $Mode -ne 'Run'
$requiresJpackage = $Mode -eq 'Package'
$requiresWix = $Mode -eq 'Package'
$javaRequirement = Get-JavaRequirementDescription $requiresBuildTools $requiresJpackage

$mutex = [System.Threading.Mutex]::new($false, 'Local\IBCManager-PrerequisiteBootstrap-v1')
$hasMutex = $false
try {
    try {
        $hasMutex = $mutex.WaitOne([TimeSpan]::FromMinutes(15))
    } catch [System.Threading.AbandonedMutexException] {
        $hasMutex = $true
    }
    if (-not $hasMutex) {
        throw 'Timed out waiting for another IBC Manager prerequisite installation to finish.'
    }

    $javaInfo = Find-CompatibleJava $requiresBuildTools $requiresJpackage
    $wixInfo = $null
    if ($requiresWix) {
        $wixInfo = Get-WixInfo
    }

    $missing = New-Object 'System.Collections.Generic.List[string]'
    if ($null -eq $javaInfo) { $missing.Add($javaRequirement) }
    if ($requiresWix -and $null -eq $wixInfo) { $missing.Add('WiX Toolset 3.x') }

    if ($requiresWix -and $null -eq $wixInfo -and
            $null -eq (Get-Command winget.exe -ErrorAction SilentlyContinue | Select-Object -First 1)) {
        throw 'WiX Toolset 3.x is missing and Windows Package Manager (winget) is unavailable. Install Microsoft App Installer or WiX Toolset 3.14.1 manually, then rerun package-windows.bat.'
    }

    if ($missing.Count -gt 0) {
        if (-not (Confirm-PrerequisiteInstallation -Missing ($missing.ToArray()) -JavaRequirement $javaRequirement)) {
            Write-Status 'Prerequisite installation was declined. No changes were made by this script.'
            exit 3
        }

        if ($missing -contains $javaRequirement) {
            Install-ManagedJdk
        }
        $javaInfo = Find-CompatibleJava $requiresBuildTools $requiresJpackage
        if ($null -eq $javaInfo) {
            throw 'A compatible Java installation could not be found after installation.'
        }

        if ($requiresWix) {
            $wixInfo = Get-WixInfo
            if ($null -eq $wixInfo -and $missing -contains 'WiX Toolset 3.x') {
                Install-WixWithWinget
                $wixInfo = Get-WixInfo
            }
            if ($null -eq $wixInfo) {
                throw 'WiX Toolset 3.x could not be found after installation. Open a new terminal or install WiX Toolset 3.14.1 manually.'
            }
        }
    }

    Write-Status ("Using Java $($javaInfo.Major) from $($javaInfo.Home)")
    if ($null -ne $wixInfo) { Write-Status ("Using WiX Toolset $($wixInfo.Version) from $($wixInfo.Bin)") }
    Write-EnvironmentBatchFile $javaInfo $wixInfo
    exit 0
} catch {
    Write-Host ''
    Write-Host ('[IBC Manager] Prerequisite setup failed: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host '[IBC Manager] No Java 8 installation was removed. Resolve the error and run the command again.'
    exit 2
} finally {
    if ($hasMutex) {
        try { $mutex.ReleaseMutex() } catch { }
    }
    $mutex.Dispose()
}
