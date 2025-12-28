param(
  [ValidateSet("ci","full")]
  [string]$Mode = "ci",
  [string]$LogPath = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
  if (-not (Test-Path ".\gradlew.bat")) {
    throw "gradlew.bat not found at repo root: $repoRoot"
  }

  $args = @(
    "--no-daemon",
    "clean",
    ":app:assembleMockDebug",
    ":app:testMockDebugUnitTest",
    ":app:lintMockDebug",
    "ktlintCheck"
  )

  if ($Mode -eq "full") {
    $args += ":app:assembleDeviceDebug"
  }

  if ($LogPath -and $LogPath.Trim().Length -gt 0) {
    $logFullPath = Join-Path $repoRoot $LogPath
    $logDir = Split-Path -Parent $logFullPath
    if ($logDir -and -not (Test-Path $logDir)) {
      New-Item -ItemType Directory -Path $logDir | Out-Null
    }

    & .\gradlew.bat @args 2>&1 | Tee-Object -FilePath $logFullPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { exit $exitCode }
  } else {
    & .\gradlew.bat @args
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { exit $exitCode }
  }
}
finally {
  Pop-Location
}
