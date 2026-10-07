$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
try {
    $javaHomeCandidate = $previousJavaHome
    $jdkIsSuitable = $false
    if ($javaHomeCandidate -and (Test-Path (Join-Path $javaHomeCandidate 'bin/javac.exe'))) {
        $compilerVersion = & (Join-Path $javaHomeCandidate 'bin/javac.exe') -version 2>&1
        $jdkIsSuitable = "$compilerVersion" -match 'javac (\d+)' -and [int]$Matches[1] -ge 25
    }
    if (-not $jdkIsSuitable) {
        # The PATH java can differ from JAVA_HOME, which is common with multiple installed JDKs.
        $settings = & java -XshowSettings:properties -version 2>&1
        $homeLine = $settings | ForEach-Object { "$_" } | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if (-not $homeLine) { throw 'Cannot locate Java. Install JDK 25 or newer.' }
        $javaHomeCandidate = ($homeLine -replace '^\s*java\.home\s*=\s*', '').Trim()
        $compiler = Join-Path $javaHomeCandidate 'bin/javac.exe'
        if (-not (Test-Path $compiler)) { throw 'The PATH Java is not a JDK. Install JDK 25 or newer.' }
        $compilerVersion = & $compiler -version 2>&1
        if ("$compilerVersion" -notmatch 'javac (\d+)' -or [int]$Matches[1] -lt 25) { throw 'JDK 25 or newer is required.' }
    }
    $env:JAVA_HOME = $javaHomeCandidate
    Push-Location $PSScriptRoot
    try {
        & .\gradlew.bat :client:build @args
        if ($LASTEXITCODE -ne 0) { throw "Client build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $previousJavaHome }
