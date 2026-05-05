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
SJTU Agent 一键安装脚本（PowerShell）

用法:
  .\install.ps1 [选项]

选项:
  -Python <cmd>          指定 Python 可执行文件，默认自动探测 py / python / python3
  -SkipPlaywright        跳过 Playwright Chromium 安装
  -NoSetup               安装完成后不自动启动 sjtu-agent setup
  -ForceRecreateVenv     强制重建 .venv
  -Help                  显示帮助
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
            throw "未找到 Python: $RequestedPython"
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

    throw "未找到可用的 Python，请先安装 Python 3.10+，或使用 -Python 指定。"
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
    throw "请从仓库根目录运行这个脚本。"
}

$PythonCommand = Resolve-PythonCommand -RequestedPython $Python

Invoke-PythonCommand -PythonCommand $PythonCommand -Arguments @(
    "-c",
    "import sys; sys.exit('Python 3.10 或更高版本是必需的。') if sys.version_info < (3, 10) else None"
) -ErrorMessage "Python 版本检查失败。"

$VenvDir = Join-Path $ProjectDir ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"

if ($ForceRecreateVenv -and (Test-Path $VenvDir)) {
    Write-Log "按要求重建虚拟环境: $VenvDir"
    Remove-Item -Recurse -Force $VenvDir
}

if ((Test-Path $VenvDir) -and -not (Test-Path $VenvPython)) {
    Write-Log "检测到损坏的虚拟环境，准备重建: $VenvDir"
    Remove-Item -Recurse -Force $VenvDir
}

if (-not (Test-Path $VenvDir)) {
    Write-Log "创建虚拟环境: $VenvDir"
    Invoke-PythonCommand -PythonCommand $PythonCommand -Arguments @("-m", "venv", $VenvDir) -ErrorMessage "创建虚拟环境失败。"
}

Write-Log "升级 pip"
Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "pip", "install", "--upgrade", "pip") -ErrorMessage "升级 pip 失败。"

Write-Log "安装 SJTU Agent"
Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "pip", "install", "-e", $ProjectDir) -ErrorMessage "安装 SJTU Agent 失败。"

if (-not $SkipPlaywright) {
    Write-Log "安装 Playwright Chromium"
    Invoke-ExternalCommand -Executable $VenvPython -Arguments @("-m", "playwright", "install", "chromium") -ErrorMessage "安装 Playwright Chromium 失败。"
}

if (-not $NoSetup) {
    Write-Log "启动 sjtu-agent setup"
    & $VenvPython -m sjtu_agent setup
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "安装完成。"
Write-Host ""
Write-Host "后续常用命令："
Write-Host "  .\.venv\Scripts\Activate.ps1"
Write-Host "  python -m sjtu_agent setup"
Write-Host "  python -m sjtu_agent"