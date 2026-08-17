<#
.SYNOPSIS
  真实任务链路 smoke 测试：上传视频 → RabbitMQ → Python 分析 → 状态机落库 → 重复投递 → 断言。
  不依赖 Docker，但依赖后端 + AI + RabbitMQ 服务运行中。
.PARAMETER BaseUrl
  Java 后端地址 (默认: http://localhost:8080)
.PARAMETER AiUrl
  Python AI 服务地址 (默认: http://localhost:8000)
.PARAMETER RabbitApiUrl
  RabbitMQ Management API 地址 (默认: http://localhost:15672)
.PARAMETER RabbitUser
  RabbitMQ 管理用户 (默认: guest)
.PARAMETER RabbitPass
  RabbitMQ 管理密码 (默认: guest)
.PARAMETER FixtureDir
  视频 fixture 目录 (默认: E:\视频)
.PARAMETER Username
  测试用户 (默认: smoke_task_user)
.PARAMETER Password
  测试用户密码 (默认: SmokeTask123!)
.PARAMETER Cleanup
  开关：测试结束时删除测试用户和视频
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AiUrl = "http://localhost:8000",
    [string]$RabbitApiUrl = "http://localhost:15672",
    [string]$RabbitUser = "guest",
    [string]$RabbitPass = "guest",
    [string]$FixtureDir = "E:\视频",
    [string]$Username = "smoke_task_user",
    [string]$Password = "SmokeTask123!",
    [switch]$Cleanup
)

$ErrorActionPreference = "Stop"
$RunId = "task-flow-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$ReportDir = "$PSScriptRoot/../logs"
$null = New-Item -ItemType Directory -Path $ReportDir -Force
$ReportFile = "$ReportDir/smoke-task-flow-$RunId.json"

$CasesResult = @{}
$OverallPassed = $true

# region helpers
function Invoke-Api($Url, $Method="GET", $Body=$null, $Auth=$null, $Retries=0) {
    $params = @{ Uri = $Url; Method = $Method; ContentType = "application/json"; UseBasicParsing = $true }
    if ($Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    if ($Auth) { $params.Headers = @{ Authorization = "Bearer $Auth" } }
    for ($i=0; $i -le $Retries; $i++) {
        try {
            $resp = Invoke-RestMethod @params -TimeoutSec 10
            return $resp
        } catch {
            if ($i -eq $Retries) {
                $statusCode = $_.Exception.Response.StatusCode.value__
                $bodyText = $_.ErrorDetails.Message
                return @{ _error = $true; _status = $statusCode; _body = $bodyText }
            }
            Start-Sleep -Seconds 1
        }
    }
}

function Get-RabbitPublishBody($VideoId, $TaskId) {
    $payload = @{
        videoId = $VideoId
        taskId = $TaskId
        userId = $null
        videoPath = $null
        exerciseType = "SQUAT"
        originalFileName = "smoke.mp4"
        timestamp = [long](Get-Date -UFormat %s) * 1000
        correlationId = "video-$VideoId-attempt-1"
        messageId = "duplicate-task-$TaskId"
        schemaVersion = 1
        attempt = 1
    } | ConvertTo-Json -Compress
    return @{
        properties = @{
            content_type = "application/json"
            message_id = "duplicate-task-$TaskId"
            correlation_id = "video-$VideoId-attempt-1"
        }
        routing_key = "video-analysis-routing-key"
        payload = $payload
        payload_encoding = "string"
    }
}

function Get-Token() {
    $login = Invoke-Api "$BaseUrl/api/users/login" POST @{ username = $Username; password = $Password }
    if ($login._error) { throw "Login failed: $($login._body)" }
    return $login.token
}

function Wait-ForTerminal($VideoId, $Token, $TimeoutSec=120) {
    $start = Get-Date
    $delay = 1000
    do {
        $status = Invoke-Api "$BaseUrl/api/videos/$VideoId/status" GET $null $Token
        if ($status._error) {
            Write-Warning "Status poll error: $($status._body)"
            Start-Sleep -Seconds 2; continue
        }
        $s = $status.status
        if ($s -eq "COMPLETED" -or $s -eq "FAILED" -or $s -eq "CANCELLED") {
            return @{ status = $s; errorCode = $status.errorCode; errorMessage = $status.errorMessage; pollCount = 0 }
        }
        # retryAfterMs from server, else exponential backoff
        $serverDelay = [int]$status.retryAfterMs
        $delay = if ($serverDelay -gt 0) { [Math]::Min($serverDelay, 8000) } else { [Math]::Min($delay * 2, 8000) }
        Start-Sleep -Milliseconds $delay
    } while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSec)
    throw "Timeout waiting for terminal state videoId=$VideoId"
}

function Get-Metrics($Token) {
    $raw = Invoke-Api "$BaseUrl/actuator/prometheus" GET $null $Token
    if ($raw._error) { return @{} }
    $lines = ($raw -split "`n") | Where-Object { $_ -match '^ai_sport_|^task_' }
    $metrics = @{}
    foreach ($line in $lines) {
        $parts = $line -split '\s+'
        if ($parts.Count -ge 2 -and $parts[0] -notmatch '^#') {
            $key = $parts[0]
            $val = [double]$parts[1]
            $metrics[$key] = $val
        }
    }
    return $metrics
}

function Assert-MetricDelta($Before, $After, $Name, $MinDelta) {
    $b = $Before[$Name] -or 0; $a = $After[$Name] -or 0
    return [Math]::Max(0, $a - $b) -ge $MinDelta
}
# endregion

function Run-SuccessCase($Case) {
    $name = $Case.name
    Write-Host "`n=== Case: $name ===" -ForegroundColor Cyan
    $result = @{ name = $name; passed = $false; steps = @{} }

    $filePath = Join-Path $FixtureDir $Case.file
    if (-not (Test-Path $filePath)) {
        $result.error = "Fixture not found: $filePath"
        Write-Warning $result.error
        return $result
    }

    try {
        # Get token
        $token = Get-Token
        $metricsBefore = Get-Metrics $token

        # Upload
        $upload = Invoke-WebRequest -Uri "$BaseUrl/api/videos/upload" -Method POST `
            -Form @{ file = Get-Item $filePath; exerciseType = $Case.exerciseType; username = $Username } `
            -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing
        $uploadData = $upload.Content | ConvertFrom-Json
        $videoId = $uploadData.videoId
        $taskId = $uploadData.taskId
        $result.steps.upload = @{ videoId = $videoId; taskId = $taskId }
        Write-Host "  Uploaded videoId=$videoId taskId=$taskId"

        # Wait for terminal
        $terminal = Wait-ForTerminal $videoId $token
        $result.steps.terminal = $terminal
        Write-Host "  Terminal status: $($terminal.status)"

        # Assert status
        $expected = $Case.expectedFinalStatus
        if ($terminal.status -ne $expected) {
            $result.error = "Expected $expected but got $($terminal.status)"
        } else {
            $result.steps.statusAssert = "PASS"
        }

        # Fetch analysis once
        $analysis = Invoke-Api "$BaseUrl/api/videos/$videoId/analysis" GET $null $token
        $result.steps.analysisFetched = ($analysis._error -eq $null -or $analysis._error -eq $false)
        Write-Host "  Analysis fetched: $($result.steps.analysisFetched)"

        # Read metrics after
        $metricsAfter = Get-Metrics $token

        # Duplicate: re-publish the same RabbitMQ message
        try {
            $dupBody = Get-RabbitPublishBody $videoId $taskId
            $dupResp = Invoke-RestMethod -Uri "$RabbitApiUrl/api/exchanges/%2f/video-analysis-exchange/publish" `
                -Method POST -Body ($dupBody | ConvertTo-Json -Compress) `
                -ContentType "application/json" -Credential (New-Object System.Management.Automation.PSCredential($RabbitUser, (ConvertTo-SecureString $RabbitPass -AsPlainText -Force))) `
                -UseBasicParsing -TimeoutSec 5
            $result.steps.duplicatePublished = $dupResp.routed -eq $true
        } catch {
            $result.steps.duplicatePublished = $false
            $result.steps.duplicateError = $_.Exception.Message
        }

        # Wait a few seconds for consumer to process (or skip)
        Start-Sleep -Seconds 3
        $metricsAfterDup = Get-Metrics $token

        # Assert: duplicate skip >= 1
        $dupDelta = [Math]::Max(0, ($metricsAfterDup.task_duplicate_skip_total -or 0) - ($metricsBefore.task_duplicate_skip_total -or 0))
        $result.steps.duplicateSkipDelta = $dupDelta
        $result.steps.dupSkipAssert = ($dupDelta -ge 1)

        # Assert: status transition >= 1
        $transDelta = [Math]::Max(0, ($metricsAfterDup.task_status_transition_total -or 0) - ($metricsBefore.task_status_transition_total -or 0))
        $result.steps.transitionDelta = $transDelta
        $result.steps.transitionAssert = ($transDelta -ge 1)

        $result.passed = ($result.steps.statusAssert -eq "PASS" -and $result.steps.dupSkipAssert)
        if ($result.passed) { Write-Host "  PASS" -ForegroundColor Green } else { Write-Host "  FAIL" -ForegroundColor Red }
    } catch {
        $result.error = $_.Exception.Message
        Write-Warning "  Error: $($result.error)"
    }
    return $result
}

# region main
try {
    # Prechecks
    Write-Host "=== Smoke Task Flow: $RunId ===" -ForegroundColor Magenta
    Write-Host "BaseUrl: $BaseUrl | AiUrl: $AiUrl | Rabbit: $RabbitApiUrl"
    Write-Host "FixtureDir: $FixtureDir (exists: $(Test-Path $FixtureDir))"
    Write-Host ""

    # 1. Health checks
    $healthBackend = Invoke-Api "$BaseUrl/api/system/health"
    $healthBackendOk = $healthBackend -and (-not $healthBackend._error)
    Write-Host "Backend health: $(if($healthBackendOk){'OK'}else{'FAIL'})"

    $healthAi = Invoke-Api "$AiUrl/health"
    $healthAiOk = $healthAi -and (-not $healthAi._error)
    Write-Host "AI service health: $(if($healthAiOk){'OK'}else{'FAIL'})"

    $rabbitCheck = Invoke-WebRequest -Uri "$RabbitApiUrl/api/overview" -UseBasicParsing -TimeoutSec 5
    $rabbitOk = $rabbitCheck.StatusCode -eq 200
    Write-Host "RabbitMQ: $(if($rabbitOk){'OK'}else{'FAIL'})"

    if (-not ($healthBackendOk -and $healthAiOk -and $rabbitOk)) {
        Write-Host "`nPrecondition failed. Start services first: backend (8080), AI service (8000), RabbitMQ (15672)" -ForegroundColor Red
        $OverallPassed = $false
        return
    }

    # Register user if needed
    $register = Invoke-Api "$BaseUrl/api/users/register" POST @{ username = $Username; password = $Password }
    if (-not $register._error) { Write-Host "User registered: $Username" }

    # Run each case (non-optional first)
    $manifest = Get-Content "$PSScriptRoot/fixtures/task-flow-fixtures.json" | ConvertFrom-Json
    foreach ($case in $manifest.cases) {
        if ($case.optional -eq $true) { continue }
        $result = Run-SuccessCase $case
        $CasesResult[$case.name] = $result
        if (-not $result.passed) { $OverallPassed = $false }
    }

    # Optional cases
    foreach ($case in $manifest.cases) {
        if ($case.optional -ne $true) { continue }
        $result = Run-SuccessCase $case
        $CasesResult[$case.name] = $result
        if ($result.error) { Write-Host "  (optional skipped: $($result.error))" -ForegroundColor DarkYellow }
    }

} catch {
    Write-Host "Fatal error: $_" -ForegroundColor Red
    $OverallPassed = $false
} finally {
    # Cleanup
    if ($Cleanup) {
        Write-Host "`nCleanup requested — not implemented here. Manual cleanup recommended."
    }
    # Write report
    $report = @{
        runId = $RunId
        timestamp = (Get-Date -Format o)
        fixtureDir = $FixtureDir
        cases = $CasesResult
        passed = $OverallPassed
    }
    $report | ConvertTo-Json -Depth 5 | Out-File $ReportFile -Encoding utf8
    Write-Host "`nReport saved: $ReportFile"
    Write-Host "Overall: $(if($OverallPassed){'PASS'}else{'FAIL'})" -ForegroundColor $(if($OverallPassed){'Green'}else{'Red'})
}
# endregion