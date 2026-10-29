# sqlgraph-mcp install (Windows PowerShell)
#
#   irm https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.ps1 | iex
#
# Environment variables
#   SQLGRAPH_ZIP          local distribution zip to use instead of downloading
#                         (needed while the repository is private: download it from Releases)
#   SQLGRAPH_VERSION      release tag to install (default: latest, e.g. v0.2.0)
#   SQLGRAPH_INSTALL_DIR  install folder (default: %USERPROFILE%\.sqlgraph\app)
#   SQLGRAPH_NO_REGISTER  set to 1 to skip Claude Code registration
#
# Messages are in English on purpose: Windows PowerShell 5.1 reads scripts without a BOM
# in the system code page, which breaks non-ASCII text.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar

$Repo = 'ysw2076/sqlgraph-mcp'
$InstallDir = if ($env:SQLGRAPH_INSTALL_DIR) { $env:SQLGRAPH_INSTALL_DIR } else { Join-Path $HOME '.sqlgraph\app' }
# Only x64 builds are published; Windows on ARM runs them through emulation.
$Asset = 'sqlgraph-mcp-windows-x64.zip'

$Tmp = Join-Path ([IO.Path]::GetTempPath()) ('sqlgraph-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Tmp | Out-Null
try {
    $Zip = Join-Path $Tmp $Asset
    if ($env:SQLGRAPH_ZIP) {
        if (-not (Test-Path $env:SQLGRAPH_ZIP)) { throw "SQLGRAPH_ZIP not found: $env:SQLGRAPH_ZIP" }
        Copy-Item $env:SQLGRAPH_ZIP $Zip
    } else {
        $Url = if ($env:SQLGRAPH_VERSION) {
            "https://github.com/$Repo/releases/download/$env:SQLGRAPH_VERSION/$Asset"
        } else {
            "https://github.com/$Repo/releases/latest/download/$Asset"
        }
        Write-Host "Downloading $Url"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        try {
            Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing
        } catch {
            throw "Download failed. If the repository is private, download $Asset from the Releases page and set SQLGRAPH_ZIP to its path."
        }
    }

    Write-Host 'Extracting...'
    $X = Join-Path $Tmp 'x'
    Expand-Archive -Path $Zip -DestinationPath $X -Force
    $Src = Join-Path $X 'sqlgraph'
    if (-not (Test-Path (Join-Path $Src 'bin\sqlgraph.cmd'))) { throw 'Unexpected archive layout' }

    if (Test-Path $InstallDir) {
        try {
            Remove-Item -Recurse -Force $InstallDir
        } catch {
            throw "Cannot replace $InstallDir. Close Claude Code (the MCP server may be running) and try again."
        }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $InstallDir) | Out-Null
    Move-Item $Src $InstallDir

    $Bin = Join-Path $InstallDir 'bin'
    $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $UserPath) { $UserPath = '' }
    if (-not (($UserPath -split ';') -contains $Bin)) {
        $NewPath = if ($UserPath.Trim()) { "$UserPath;$Bin" } else { $Bin }
        [Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')
        Write-Host "Added to user PATH: $Bin (open a new terminal to use 'sqlgraph')"
    }
    $env:Path = "$env:Path;$Bin"

    Write-Host "Installed: $InstallDir"
    & (Join-Path $Bin 'sqlgraph.cmd') --version

    if ($env:SQLGRAPH_NO_REGISTER -ne '1') {
        Write-Host ''
        & (Join-Path $Bin 'sqlgraph.cmd') install
    }
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
