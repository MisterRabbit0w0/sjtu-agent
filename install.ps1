[CmdletBinding()]
param(
    [string]$Python,
    [switch]$SkipPlaywright,
    [switch]$NoSetup,
    [switch]$ForceRecreateVenv,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @"
SJTU Agent installer (PowerShell)

Usage:
  .\install.ps1 [options]

Options:
  -Python <cmd>          Specify the Python executable (default: auto-detect py / python / python3)
  -SkipPlaywright        Skip Playwright Chromium installation
  -NoSetup               Do not run sjtu-agent setup after installation
  -ForceRecreateVenv     Force recreate the .venv virtual environment
  -Help                  Show this help message
"@
}

function Write-Log {
    param([string]$Message)

    Write-Host ""
    Write-Host "[sjtu-agent-install] $Message"
}

function Resolve-PythonCommand {
    param([string]$RequestedPython)

    if ($RequestedPython) {
        if (-not (Get-Command $RequestedPython -ErrorAction SilentlyContinue)) {
            throw "Python executable not found: $RequestedPython"
        }
        return [pscustomobject]@{
            Executable = $RequestedPython
            PrefixArgs = @()
        }
    }

    $candidates = @(
        @{ Executable = "py";      PrefixArgs = @("-3") },
        @{ Executable = "python";  PrefixArgs = @() },
        @{ Executable = "python3"; PrefixArgs = @() }
    )

    foreach ($candidate in $candidates) {
        if (-not (Get-Command $candidate.Executable -ErrorAction SilentlyContinue)) {
            continue
        }

        try {
            & $candidate.Executable @($candidate.PrefixArgs + @("--version")) *> $null
            if ($LASTEXITCODE -eq 0) {
                return [pscustomobject]@{
                    Executable = $candidate.Executable
                    PrefixArgs = $candidate.PrefixArgs
                }
            }
        }
        catch {
        }
    }

    throw "No usable Python found. Please install Python 3.10+ or specify one with -Python."
}

function Invoke-PythonCommand {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$PythonCommand,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$ErrorMessage
    )

    & $PythonCommand.Executable @($PythonCommand.PrefixArgs + $Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw $ErrorMessage
    }
}

function Invoke-ExternalCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$ErrorMessage
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw $ErrorMessage
    }
}

if ($Help) {
    Show-Help
    exit 0
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = $ScriptDir

if (-not (Test-Path (Join-Path $ProjectDir "pyproject.toml"))) {
    throw "Please run this script from the repository root directory."
}

$PythonCommand = Resolve-PythonCommand -RequestedPython $Python

Invoke-PythonCommand -PythonCommand $PythonCommand -Arguments @(
    "-c",
    "import sys; sys.exit('Python 3.10 or higher is required.') if sys.version_info < (3, 10) else None"
) -ErrorMessage "Python version check failed."

$VenvDir = Join-Path $ProjectDir ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"

if ($ForceRecreateVenv -and (Test-Path $VenvDir)) {
    Write-Log "Recreating virtual environment: $VenvDir"
    Remove-Item -Recurse -Force $VenvDir
}

if ((Test-Path $VenvDir) -and -not (Test-Path $VenvPython)) {
    Write-Log "Detected broken virtual environment, recreating: $VenvDir"
    Remove-Item -Recurse -Force $VenvDir
}

if (-not (Test-Path $VenvDir)) {
    Write-Log "Creating virtual environment: $VenvDir"
    Invoke-PythonCommand -PythonCommand $PythonCommand -Arguments @("-m", "venv", $VenvDir) -ErrorMessage "Failed to create virtual environment."
}

Write-Log "Upgrading pip"
Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "pip", "install", "--upgrade", "pip") -ErrorMessage "Failed to upgrade pip."

Write-Log "Installing SJTU Agent"
Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "pip", "install", "-e", $ProjectDir) -ErrorMessage "Failed to install SJTU Agent."

if (-not $SkipPlaywright) {
    Write-Log "Installing Playwright Chromium"
    Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "playwright", "install", "chromium") -ErrorMessage "Failed to install Playwright Chromium."
}

if (-not $NoSetup) {
    Write-Log "Launching sjtu-agent setup"
    & $VenvPython -m sjtu_agent setup
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Installation complete."
Write-Host ""
Write-Host "Next steps:"
Write-Host "  .\.venv\Scripts\Activate.ps1"
Write-Host "  python -m sjtu_agent setup"
Write-Host "  python -m sjtu_agent"
