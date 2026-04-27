param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$PythonRoot = "D:\BaiduNetdiskDownload\Ai-Sport(python)",
    [string]$CosRegion = "ap-guangzhou",
    [string]$CosBucket,
    [string]$CosSecretId,
    [string]$CosSecretKey,
    [string]$CosPublicBaseUrl,
    [int]$AiPort = 8000,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [switch]$SkipRabbitMq
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-NonEmpty([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Missing required parameter: $Name"
    }
}

Assert-NonEmpty -Name "CosBucket" -Value $CosBucket
Assert-NonEmpty -Name "CosSecretId" -Value $CosSecretId
Assert-NonEmpty -Name "CosSecretKey" -Value $CosSecretKey
Assert-NonEmpty -Name "CosPublicBaseUrl" -Value $CosPublicBaseUrl

# Java (Spring Boot) COS settings
$env:DEV_OBJECT_STORAGE_ENABLED = "true"
$env:DEV_OBJECT_STORAGE_PROVIDER = "cos"
$env:DEV_OBJECT_STORAGE_REGION = $CosRegion
$env:DEV_OBJECT_STORAGE_BUCKET = $CosBucket
$env:DEV_OBJECT_STORAGE_ACCESS_KEY = $CosSecretId
$env:DEV_OBJECT_STORAGE_SECRET_KEY = $CosSecretKey
$env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL = $CosPublicBaseUrl

# Python AI COS settings
$env:AI_COS_ENABLED = "true"
$env:AI_COS_REGION = $CosRegion
$env:AI_COS_BUCKET = $CosBucket
$env:AI_COS_SECRET_ID = $CosSecretId
$env:AI_COS_SECRET_KEY = $CosSecretKey
$env:AI_COS_PUBLIC_BASE_URL = $CosPublicBaseUrl

Write-Host "COS env injected (region=$CosRegion, bucket=$CosBucket)."

$baseScript = Join-Path $PSScriptRoot "dev_up.ps1"
if (-not (Test-Path $baseScript)) {
    throw "Base startup script not found: $baseScript"
}

$args = @(
    "-ExecutionPolicy", "Bypass",
    "-File", $baseScript,
    "-RepoRoot", $RepoRoot,
    "-PythonRoot", $PythonRoot,
    "-AiPort", "$AiPort",
    "-BackendPort", "$BackendPort",
    "-FrontendPort", "$FrontendPort"
)
if ($SkipRabbitMq.IsPresent) {
    $args += "-SkipRabbitMq"
}

& powershell @args
