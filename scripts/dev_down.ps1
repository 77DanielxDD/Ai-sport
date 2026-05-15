param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [switch]$StopRabbitMq
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runDir = Join-Path $RepoRoot ".run"

function Write-Step([string]$msg) {
    Write-Host "`n=== $msg ==="
}

function Get-PidFile([string]$name) {
    return Join-Path $runDir "$name.pid"
}

function Stop-ManagedProcess([string]$name) {
    $pidFile = Get-PidFile $name
    if (-not (Test-Path $pidFile)) {
        Write-Host "$name not running (no pid file)."
        return
    }

    $pidText = (Get-Content -Path $pidFile -Raw).Trim()
    $id = 0
    if (-not [int]::TryParse($pidText, [ref]$id)) {
        Write-Warning "$name pid file is invalid: $pidText"
        Remove-Item -Force $pidFile
        return
    }

    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        Write-Host "$name already stopped (PID=$id not found)."
        Remove-Item -Force $pidFile
        return
    }

    try {
        Stop-Process -Id $id -Force
        Write-Host "$name stopped (PID=$id)."
    } catch {
        Write-Warning "Failed to stop $name (PID=$id): $($_.Exception.Message)"
    } finally {
        if (Test-Path $pidFile) {
            Remove-Item -Force $pidFile
        }
    }
}

Write-Step "1) Stop frontend/backend/AI"
Stop-ManagedProcess "frontend"
Stop-ManagedProcess "backend"
Stop-ManagedProcess "ai"

Write-Step "2) Optional RabbitMQ stop"
if ($StopRabbitMq) {
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCmd) {
        Write-Warning "docker command not found. Skip RabbitMQ stop."
    } else {
        Push-Location $RepoRoot
        try {
            & docker compose stop rabbitmq | Out-Host
        } finally {
            Pop-Location
        }
    }
} else {
    Write-Host "RabbitMQ kept running. Use -StopRabbitMq to stop it."
}

Write-Step "Done"
Write-Host "You can restart everything with:"
Write-Host "  powershell -ExecutionPolicy Bypass -File scripts/dev_up.ps1"
