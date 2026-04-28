param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$JavaHome = "E:\Amazon Corretto\jdk21.0.11_10",
    [string]$MavenHome = "E:\Apache\apache-maven-3.9.15",
    [string]$GitHome = "E:\Git",
    [string]$PythonExe = "python",
    [string]$CosRegion = "ap-guangzhou",
    [string]$CosBucket = "dcq-1361716063",
    [string]$CosSecretId = "",
    [string]$CosSecretKey = "",
    [string]$CosPublicBaseUrl = "",
    [switch]$SkipFrontend,
    [switch]$SkipRedis
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($CosSecretId)) {
    $CosSecretId = $env:DEV_OBJECT_STORAGE_ACCESS_KEY
}
if ([string]::IsNullOrWhiteSpace($CosSecretKey)) {
    $CosSecretKey = $env:DEV_OBJECT_STORAGE_SECRET_KEY
}
if ([string]::IsNullOrWhiteSpace($CosPublicBaseUrl)) {
    $CosPublicBaseUrl = "https://$CosBucket.cos.$CosRegion.myqcloud.com"
}
if ([string]::IsNullOrWhiteSpace($CosSecretId) -or [string]::IsNullOrWhiteSpace($CosSecretKey)) {
    throw "Missing COS credentials. Pass -CosSecretId/-CosSecretKey or set DEV_OBJECT_STORAGE_ACCESS_KEY/DEV_OBJECT_STORAGE_SECRET_KEY."
}

$oneClick = Join-Path $PSScriptRoot "one_click_up.ps1"
if (-not (Test-Path $oneClick)) {
    throw "Required script not found: $oneClick"
}

$params = @{
    RepoRoot         = $RepoRoot
    JavaHome         = $JavaHome
    MavenHome        = $MavenHome
    GitHome          = $GitHome
    PythonExe        = $PythonExe
    CosRegion        = $CosRegion
    CosBucket        = $CosBucket
    CosSecretId      = $CosSecretId
    CosSecretKey     = $CosSecretKey
    CosPublicBaseUrl = $CosPublicBaseUrl
}
if ($SkipFrontend.IsPresent) {
    $params["SkipFrontend"] = $true
}
if ($SkipRedis.IsPresent) {
    $params["SkipRedis"] = $true
}

& powershell -ExecutionPolicy Bypass -File $oneClick @params
