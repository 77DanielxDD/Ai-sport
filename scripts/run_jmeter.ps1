param(
  [string]$JmeterBin = "jmeter",
  [string]$Plan = "jmeter/ai-sport-upload-poll.jmx",
  [string]$ResultJtl = "jmeter/results/run.jtl",
  [string]$ReportDir = "jmeter/results/html",
  [string]$TargetHost = "127.0.0.1",
  [int]$Port = 8080,
  [string]$Protocol = "http",
  [int]$DurationSec = 120,
  [int]$PollMax = 120,
  [int]$PollIntervalMs = 1000,
  [string]$CsvPath = "jmeter/testdata/upload_cases.csv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ResultJtl) | Out-Null
if (Test-Path $ReportDir) {
  Remove-Item -Recurse -Force $ReportDir
}
if (Test-Path $ResultJtl) {
  Remove-Item -Force $ResultJtl
}

$args = @(
  "-n",
  "-f",
  "-t", $Plan,
  "-l", $ResultJtl,
  "-e", "-o", $ReportDir,
  "-JHOST=$TargetHost",
  "-JPORT=$Port",
  "-JPROTOCOL=$Protocol",
  "-JDURATION_SEC=$DurationSec",
  "-JPOLL_MAX=$PollMax",
  "-JPOLL_INTERVAL_MS=$PollIntervalMs",
  "-JCSV_PATH=$CsvPath"
)

& $JmeterBin @args
if ($LASTEXITCODE -ne 0) {
  throw "JMeter exited with code $LASTEXITCODE"
}

Write-Host "JMeter run finished."
Write-Host "JTL: $ResultJtl"
Write-Host "HTML report: $ReportDir/index.html"
