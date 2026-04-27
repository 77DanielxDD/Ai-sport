param(
  [string]$BaseUrl = "http://127.0.0.1:8080",
  [string]$Username = "test_user_2",
  [string]$Password = "123456",
  [string]$VideoPath = "",
  [string]$ExerciseType = "BENCH_PRESS",
  [int]$PollMax = 60,
  [int]$PollIntervalMs = 1000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($VideoPath)) {
  throw "VideoPath is required. Example: -VideoPath D:\videos\demo.mp4"
}
if (-not (Test-Path -Path $VideoPath)) {
  throw "Video file not found: $VideoPath"
}

Write-Host "[1/4] Login..." -ForegroundColor Cyan
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$loginResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/users/login" -ContentType "application/json" -Body $loginBody
$token = $loginResp.token
if ([string]::IsNullOrWhiteSpace($token)) {
  throw "Login failed: token missing"
}
$headers = @{ Authorization = "Bearer $token"; Accept = "application/json" }
Write-Host "Login OK. user=$($loginResp.username)"

Write-Host "[2/4] Upload video..." -ForegroundColor Cyan
$uploadRaw = & curl.exe -s -X POST "$BaseUrl/api/videos/upload" `
  -H "Authorization: Bearer $token" `
  -H "Accept: application/json" `
  -F "file=@$VideoPath" `
  -F "exerciseType=$ExerciseType"
$uploadResp = $uploadRaw | ConvertFrom-Json
$videoId = $uploadResp.videoId
if (-not $videoId) {
  throw "Upload failed: videoId missing. Response: $($uploadResp | ConvertTo-Json -Depth 5)"
}
Write-Host "Upload OK. videoId=$videoId taskId=$($uploadResp.taskId)"

Write-Host "[3/4] Poll analysis..." -ForegroundColor Cyan
$analysis = $null
for ($i = 0; $i -lt $PollMax; $i++) {
  try {
    $analysis = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/videos/$videoId/analysis" -Headers $headers
    if ($analysis.status -eq "COMPLETED") {
      Write-Host "COMPLETED at try=$i"
      break
    }
  } catch {
    $resp = $_.Exception.Response
    if ($null -ne $resp) {
      $code = [int]$resp.StatusCode
      $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
      $body = $reader.ReadToEnd()
      if ($code -eq 202) {
        Write-Host "try=$i status=PROCESSING"
      } elseif ($code -eq 500 -or $code -eq 409) {
        throw "Analysis failed. HTTP=$code body=$body"
      } else {
        throw "Unexpected HTTP=$code body=$body"
      }
    } else {
      throw
    }
  }
  Start-Sleep -Milliseconds $PollIntervalMs
}

if ($null -eq $analysis -or $analysis.status -ne "COMPLETED") {
  throw "Polling timeout. videoId=$videoId"
}

Write-Host "[4/4] Validate report payload..." -ForegroundColor Cyan
$reportImages = @($analysis.analysis.report_images)
$tips = @($analysis.analysis.tips)
if ($reportImages.Count -eq 0) {
  throw "report_images is empty"
}
if ($tips.Count -eq 0) {
  throw "tips is empty"
}

$result = [PSCustomObject]@{
  videoId = $analysis.videoId
  status = $analysis.status
  repCount = $analysis.analysis.rep_count
  processingTimeMs = $analysis.analysis.processing_time_ms
  endToEndMs = $analysis.endToEndMs
  reportImageCount = $reportImages.Count
  tipsCount = $tips.Count
}

Write-Host "E2E smoke test passed." -ForegroundColor Green
$result | Format-List
