# One-click dev environment startup
# Double-click this file or run:
#   powershell -ExecutionPolicy Bypass -File scripts/run_dev.ps1

param(
    [string]$DbPassword = $env:DEV_DB_PASSWORD,
    [string]$LlmApiKey = $env:APP_LLM_API_KEY,
    [switch]$SkipAi,
    [switch]$SkipFrontend
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path $PSScriptRoot -Parent
$RunDir = Join-Path $RepoRoot ".run"
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Save-Pid([string]$Name, [int]$Id) {
    Set-Content -Path (Join-Path $RunDir "$Name.pid") -Value $Id
}

function Start-ManagedProcess([string]$Name, [string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory) {
    $outLog = Join-Path $RunDir "$Name.out.log"
    $errLog = Join-Path $RunDir "$Name.err.log"
    $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
    Save-Pid $Name $p.Id
    Write-Host "$Name started (PID=$($p.Id))." -ForegroundColor Green
}

function Test-HttpOk([string]$Url) {
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        return $resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500
    } catch {
        return $false
    }
}

# ── Secrets (set your keys here, or pass via env vars) ──────────────────
$CosRegion     = "ap-guangzhou"
$CosBucket     = if ($env:DEV_OBJECT_STORAGE_BUCKET) { $env:DEV_OBJECT_STORAGE_BUCKET } else { "dcq-1361716063" }
$CosSecretId   = if ($env:DEV_OBJECT_STORAGE_ACCESS_KEY) { $env:DEV_OBJECT_STORAGE_ACCESS_KEY } else { "" }
$CosSecretKey  = if ($env:DEV_OBJECT_STORAGE_SECRET_KEY) { $env:DEV_OBJECT_STORAGE_SECRET_KEY } else { "" }
$CosPublicBaseUrl = if ($env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL) { $env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL } else { "https://$CosBucket.cos.$CosRegion.myqcloud.com" }

if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    Write-Host "DEV_DB_PASSWORD not set. Trying default..." -ForegroundColor Yellow
    $DbPassword = "changeme"
}

$hasCos = -not [string]::IsNullOrWhiteSpace($CosSecretId) -and -not [string]::IsNullOrWhiteSpace($CosSecretKey)
if (-not $hasCos) {
    Write-Host "COS credentials not set. Starting without COS storage." -ForegroundColor Yellow
}

# ── 1) Clean stale processes ────────────────────────────────────────────
Push-Location $RepoRoot
try {
    & powershell -ExecutionPolicy Bypass -File "scripts/dev_down.ps1"
} finally { Pop-Location }

# ── 2) Start Docker infrastructure (Redis + RabbitMQ, skip MySQL) ───────
Write-Host "`n=== Starting Docker infrastructure ===" -ForegroundColor Cyan
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($null -ne $dockerCmd) {
    Push-Location $RepoRoot
    try {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & docker compose up -d redis rabbitmq --remove-orphans 2>&1 | Out-Host
        $ErrorActionPreference = $prev
        Write-Host "Docker services started." -ForegroundColor Green
    } finally { Pop-Location }
} else {
    Write-Host "docker not found. Skip." -ForegroundColor Yellow
}

# ── 3) Start AI service ─────────────────────────────────────────────────
if (-not $SkipAi) {
    Write-Host "`n=== Starting AI service ===" -ForegroundColor Cyan
    $aiDir = Join-Path $RepoRoot "ai-service"
    if (-not (Test-Path $aiDir)) {
        Write-Host "ai-service/ not found. Skip." -ForegroundColor Yellow
    } else {
        if (-not (Test-HttpOk "http://127.0.0.1:8000/health")) {
            $mediaBaseDir = Join-Path $RepoRoot "uploaded-videos\output"
            New-Item -ItemType Directory -Force -Path $mediaBaseDir | Out-Null
            New-Item -ItemType Directory -Force -Path (Join-Path $aiDir ".mplconfig") | Out-Null
            Start-ManagedProcess "ai" "cmd.exe" @("/c", "set MPLCONFIGDIR=$aiDir\.mplconfig && set AI_MEDIA_BASE_DIR=$mediaBaseDir && python -m uvicorn app.main:app --host 127.0.0.1 --port 8000") $aiDir
        } else {
            Write-Host "AI service already running." -ForegroundColor Green
        }
    }
}

# ── 4) Start Spring Boot ────────────────────────────────────────────────
Write-Host "`n=== Starting backend ===" -ForegroundColor Cyan
$mediaOutputDir = Join-Path $RepoRoot "uploaded-videos\output"
$backendEnv = @(
    "set DEV_DB_PASSWORD=$DbPassword",
    "set APP_MEDIA_BASE_DIR=$mediaOutputDir",
    "set DEV_MEDIA_BASE_DIR=$mediaOutputDir",
    "set APP_REDIS_CACHE_ENABLED=true",
    "set APP_REDIS_HOST=127.0.0.1",
    "set APP_REDIS_PORT=6379",
    "set JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Dspring.main.lazy-initialization=false"
)

if ($hasCos) {
    $backendEnv += @(
        "set DEV_OBJECT_STORAGE_ENABLED=true",
        "set DEV_OBJECT_STORAGE_PROVIDER=cos",
        "set DEV_OBJECT_STORAGE_REGION=$CosRegion",
        "set DEV_OBJECT_STORAGE_BUCKET=$CosBucket",
        "set DEV_OBJECT_STORAGE_ACCESS_KEY=$CosSecretId",
        "set DEV_OBJECT_STORAGE_SECRET_KEY=$CosSecretKey",
        "set DEV_OBJECT_STORAGE_PUBLIC_BASE_URL=$CosPublicBaseUrl"
    )
}

if (-not [string]::IsNullOrWhiteSpace($LlmApiKey)) {
    $backendEnv += "set APP_LLM_API_KEY=$LlmApiKey"
}

$envChain = ($backendEnv -join " && ") + " && mvn -q -DskipTests spring-boot:run"
Start-ManagedProcess "backend" "cmd.exe" @("/c", $envChain) $RepoRoot

# ── 5) Start frontend ───────────────────────────────────────────────────
if (-not $SkipFrontend) {
    Write-Host "`n=== Starting frontend ===" -ForegroundColor Cyan
    $frontendDir = Join-Path $RepoRoot "frontend"
    if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
        Write-Host "Installing frontend dependencies..." -ForegroundColor Yellow
        Push-Location $frontendDir
        try { & cmd.exe /c "npm install" | Out-Host } finally { Pop-Location }
    }
    Start-ManagedProcess "frontend" "cmd.exe" @("/c", "npm run dev -- --host 0.0.0.0 --port 5173") $frontendDir
}

Write-Host "`n=== Done ===" -ForegroundColor Green
Write-Host "AI:       http://127.0.0.1:8000/health"
Write-Host "Backend:  http://127.0.0.1:8080/api/system/health"
if (-not $SkipFrontend) { Write-Host "Frontend: http://127.0.0.1:5173" }
Write-Host "Stop:     powershell -ExecutionPolicy Bypass -File scripts/dev_down.ps1"
