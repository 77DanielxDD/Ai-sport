param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$JavaHome = "E:\Amazon Corretto\jdk21.0.11_10",
    [string]$MavenHome = "E:\Apache\apache-maven-3.9.15",
    [string]$GitHome = "E:\Git",
    [string]$PythonExe = "python",
    [int]$AiPort = 8000,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [string]$MediaBaseDir = "",
    [string]$CosRegion = "ap-guangzhou",
    [string]$CosBucket = "",
    [string]$CosSecretId = "",
    [string]$CosSecretKey = "",
    [string]$CosPublicBaseUrl = "",
    [string]$DbPassword = "",
    [switch]$SkipRabbitMq,
    [switch]$SkipRedis,
    [switch]$SkipFrontend
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runDir = Join-Path $RepoRoot ".run"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" }

function Require-NonEmpty([string]$name, [string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing required parameter: $name" }
}

function Get-PidFile([string]$name) { Join-Path $runDir "$name.pid" }
function Save-Pid([string]$name, [int]$procId) { Set-Content -Path (Get-PidFile $name) -Value $procId -Encoding ascii }

function Get-ExistingProcess([string]$name) {
    $pidFile = Get-PidFile $name
    if (-not (Test-Path $pidFile)) { return $null }
    $pidText = (Get-Content -Path $pidFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($pidText)) { return $null }
    $id = 0
    if (-not [int]::TryParse($pidText, [ref]$id)) { return $null }
    Get-Process -Id $id -ErrorAction SilentlyContinue
}

function Start-ManagedProcess([string]$Name,[string]$FilePath,[string[]]$ArgumentList,[string]$WorkingDirectory) {
    $existing = Get-ExistingProcess $Name
    if ($null -ne $existing) { Write-Host "$Name already running (PID=$($existing.Id))."; return }
    $outLog = Join-Path $runDir "$Name.out.log"
    $errLog = Join-Path $runDir "$Name.err.log"
    $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Minimized -PassThru
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
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500) { Write-Host "$Name ready: $Url"; return $true }
        } catch {}
        Start-Sleep -Milliseconds 800
    }
    Write-Warning "$Name health check timeout: $Url"
    return $false
}

if ([string]::IsNullOrWhiteSpace($CosBucket)) { $CosBucket = $env:DEV_OBJECT_STORAGE_BUCKET }
if ([string]::IsNullOrWhiteSpace($CosSecretId)) { $CosSecretId = $env:DEV_OBJECT_STORAGE_ACCESS_KEY }
if ([string]::IsNullOrWhiteSpace($CosSecretKey)) { $CosSecretKey = $env:DEV_OBJECT_STORAGE_SECRET_KEY }
if ([string]::IsNullOrWhiteSpace($CosPublicBaseUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL)) { $CosPublicBaseUrl = $env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL }
    elseif (-not [string]::IsNullOrWhiteSpace($CosBucket)) { $CosPublicBaseUrl = "https://$CosBucket.cos.$CosRegion.myqcloud.com" }
}
if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    if (-not [string]::IsNullOrWhiteSpace($env:DEV_DB_PASSWORD)) { $DbPassword = $env:DEV_DB_PASSWORD }
    elseif (-not [string]::IsNullOrWhiteSpace($env:MYSQL_ROOT_PASSWORD)) { $DbPassword = $env:MYSQL_ROOT_PASSWORD }
}

Require-NonEmpty -name "CosBucket" -value $CosBucket
Require-NonEmpty -name "CosSecretId" -value $CosSecretId
Require-NonEmpty -name "CosSecretKey" -value $CosSecretKey
Require-NonEmpty -name "CosPublicBaseUrl" -value $CosPublicBaseUrl
Require-NonEmpty -name "DbPassword" -value $DbPassword

if ([string]::IsNullOrWhiteSpace($MediaBaseDir)) { $MediaBaseDir = Join-Path $RepoRoot "ai-service\uploaded-videos\output" }

Write-Step "1) Start infrastructure (RabbitMQ + Redis)"
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCmd) {
    Write-Warning "docker command not found. Skip RabbitMQ/Redis startup."
} else {
    Push-Location $RepoRoot
    try {
        if (-not $SkipRabbitMq -and -not $SkipRedis) { & docker compose up -d rabbitmq redis | Out-Host }
        elseif (-not $SkipRabbitMq) { & docker compose up -d rabbitmq | Out-Host; Write-Host "SkipRedis specified." }
        elseif (-not $SkipRedis) { & docker compose up -d redis | Out-Host; Write-Host "SkipRabbitMq specified." }
        else { Write-Host "SkipRabbitMq and SkipRedis specified." }
    } finally { Pop-Location }
}

Write-Step "2) Start local AI service"
$aiDir = Join-Path $RepoRoot "ai-service"
if (-not (Test-Path $aiDir)) { throw "AI service directory not found: $aiDir" }
New-Item -ItemType Directory -Force -Path (Join-Path $aiDir ".mplconfig") | Out-Null
Start-ManagedProcess -Name "ai" -FilePath "cmd.exe" -ArgumentList @("/c", "set `"MPLCONFIGDIR=$aiDir\.mplconfig`" && `"$PythonExe`" -m uvicorn app.main:app --host 127.0.0.1 --port $AiPort") -WorkingDirectory $aiDir
Wait-Http -Name "AI" -Url "http://127.0.0.1:$AiPort/health" -TimeoutSec 60 | Out-Null

Write-Step "3) Start Spring Boot (COS + Redis cache enabled)"
Start-ManagedProcess -Name "backend" -FilePath "cmd.exe" -ArgumentList @(
    "/c",
    "set `"JAVA_HOME=$JavaHome`" && set `"PATH=$JavaHome\bin;$MavenHome\bin;$GitHome\bin;%PATH%`" && set `"DEV_OBJECT_STORAGE_ENABLED=true`" && set `"DEV_OBJECT_STORAGE_PROVIDER=cos`" && set `"DEV_OBJECT_STORAGE_REGION=$CosRegion`" && set `"DEV_OBJECT_STORAGE_BUCKET=$CosBucket`" && set `"DEV_OBJECT_STORAGE_ACCESS_KEY=$CosSecretId`" && set `"DEV_OBJECT_STORAGE_SECRET_KEY=$CosSecretKey`" && set `"DEV_OBJECT_STORAGE_PUBLIC_BASE_URL=$CosPublicBaseUrl`" && set `"DEV_MEDIA_BASE_DIR=$MediaBaseDir`" && set `"APP_MEDIA_BASE_DIR=$MediaBaseDir`" && set `"DEV_DB_PASSWORD=$DbPassword`" && set `"APP_REDIS_CACHE_ENABLED=true`" && set `"APP_REDIS_HOST=127.0.0.1`" && set `"APP_REDIS_PORT=6379`" && set `"JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Dspring.main.lazy-initialization=false`" && mvn -q -DskipTests spring-boot:run"
) -WorkingDirectory $RepoRoot
Wait-Http -Name "Backend" -Url "http://127.0.0.1:$BackendPort/api/system/health" -TimeoutSec 120 | Out-Null

Write-Step "4) Start frontend"
if ($SkipFrontend) {
    Write-Host "SkipFrontend specified."
} else {
    $frontendDir = Join-Path $RepoRoot "frontend"
    if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
        Write-Host "Installing frontend dependencies..."
        Push-Location $frontendDir
        try { & cmd.exe /c "npm install" | Out-Host } finally { Pop-Location }
    }
    Start-ManagedProcess -Name "frontend" -FilePath "cmd.exe" -ArgumentList @("/c", "npm run dev -- --host 0.0.0.0 --port $FrontendPort") -WorkingDirectory $frontendDir
    Wait-Http -Name "Frontend" -Url "http://127.0.0.1:$FrontendPort" -TimeoutSec 60 | Out-Null
}

Write-Step "Done"
Write-Host "AI:       http://127.0.0.1:$AiPort"
Write-Host "Backend:  http://127.0.0.1:$BackendPort"
if (-not $SkipFrontend) { Write-Host "Frontend: http://127.0.0.1:$FrontendPort" }
Write-Host "Logs:     $runDir"
Write-Host "Stop all: powershell -ExecutionPolicy Bypass -File scripts/dev_down.ps1 -StopRabbitMq"
