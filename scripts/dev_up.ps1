param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$PythonRoot = "D:\BaiduNetdiskDownload\Ai-Sport(python)",
    [int]$AiPort = 8000,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [switch]$SkipRabbitMq
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runDir = Join-Path $RepoRoot ".run"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Write-Step([string]$msg) {
    Write-Host "`n=== $msg ==="
}

function Get-PidFile([string]$name) {
    return Join-Path $runDir "$name.pid"
}

function Save-Pid([string]$name, [int]$procId) {
    Set-Content -Path (Get-PidFile $name) -Value $procId -Encoding ascii
}

function Get-ExistingProcess([string]$name) {
    $pidFile = Get-PidFile $name
    if (-not (Test-Path $pidFile)) {
        return $null
    }

    $pidText = (Get-Content -Path $pidFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($pidText)) {
        return $null
    }

    $id = 0
    if (-not [int]::TryParse($pidText, [ref]$id)) {
        return $null
    }

    return Get-Process -Id $id -ErrorAction SilentlyContinue
}

function Start-ManagedProcess(
    [string]$Name,
    [string]$FilePath,
    [string[]]$ArgumentList,
    [string]$WorkingDirectory
) {
    $existing = Get-ExistingProcess $Name
    if ($null -ne $existing) {
        Write-Host "$Name already running (PID=$($existing.Id))."
        return
    }

    $outLog = Join-Path $runDir "$Name.out.log"
    $errLog = Join-Path $runDir "$Name.err.log"

    $p = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Minimized `
        -PassThru

    Save-Pid $Name $p.Id
    Write-Host "$Name started (PID=$($p.Id))."
    Write-Host "  out: $outLog"
    Write-Host "  err: $errLog"
}

function Wait-Http([string]$Name, [string]$Url, [int]$TimeoutSec) {
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSec) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -Method GET -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500) {
                Write-Host "$Name ready: $Url"
                return $true
            }
        } catch {
        }
        Start-Sleep -Milliseconds 800
    }

    Write-Warning "$Name health check timeout: $Url"
    return $false
}

Write-Step "1) Start RabbitMQ"
if (-not $SkipRabbitMq) {
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCmd) {
        Write-Warning "docker command not found. Skip RabbitMQ startup."
    } else {
        Push-Location $RepoRoot
        try {
            & docker compose up -d rabbitmq | Out-Host
        } finally {
            Pop-Location
        }
    }
} else {
    Write-Host "SkipRabbitMq specified."
}

Write-Step "2) Start AI service"
$pythonCandidates = @(
    (Join-Path $PythonRoot ".venv\Scripts\python.exe"),
    (Join-Path $PythonRoot "venv\Scripts\python.exe")
)
$pythonExe = $pythonCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $pythonExe) {
    throw "Python executable not found under: $PythonRoot"
}
Start-ManagedProcess -Name "ai" -FilePath $pythonExe -ArgumentList @("-m", "uvicorn", "ai_service.api_server:app", "--host", "127.0.0.1", "--port", "$AiPort") -WorkingDirectory $PythonRoot
Wait-Http -Name "AI" -Url "http://127.0.0.1:$AiPort/health" -TimeoutSec 60 | Out-Null

Write-Step "3) Start Spring Boot"
Start-ManagedProcess -Name "backend" -FilePath "cmd.exe" -ArgumentList @("/c", "mvn spring-boot:run") -WorkingDirectory $RepoRoot
Wait-Http -Name "Backend" -Url "http://127.0.0.1:$BackendPort/api/system/health" -TimeoutSec 90 | Out-Null

Write-Step "4) Start frontend"
$frontendDir = Join-Path $RepoRoot "frontend"
Start-ManagedProcess -Name "frontend" -FilePath "cmd.exe" -ArgumentList @("/c", "npm run dev") -WorkingDirectory $frontendDir
Wait-Http -Name "Frontend" -Url "http://127.0.0.1:$FrontendPort" -TimeoutSec 45 | Out-Null

Write-Step "Done"
Write-Host "AI:       http://127.0.0.1:$AiPort"
Write-Host "Backend:  http://127.0.0.1:$BackendPort"
Write-Host "Frontend: http://127.0.0.1:$FrontendPort"
Write-Host "Logs:     $runDir"
