# 一键起本地开发环境：后端（预打包 jar）+ 前端（Vite），**等两端都真的能响应了再打开浏览器**。
#
# 为什么要有它：`mvn spring-boot:run` 每次要从 Maven 启动 + 编译 + Spring 启动，实测 23.5s；
# 直接 `java -jar` 省掉 Maven 那 17s，只剩 Spring 自己的 ~10s。而这段时间里打开站点会看到
# 接口连不上（面板空白/报错），观感就是"网站很慢"。这个脚本把等待变成一次可见的进度提示。
#
#   .\scripts\dev-up.ps1              # 起后端+前端，就绪后自动打开浏览器
#   .\scripts\dev-up.ps1 -Rebuild     # 强制重新打包后端
#   .\scripts\dev-up.ps1 -NoBrowser   # 不打开浏览器
#   .\scripts\dev-down.ps1            # 停掉本脚本起的两端
param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [switch]$Rebuild,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs'
$pidFile = Join-Path $logDir 'dev-up.json'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# ---------- 工具函数 ----------
function Test-Port([int]$Port) {
    [bool](Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

function Wait-Http([int]$Port, [string]$Path, [int]$TimeoutSeconds, [string]$Label) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $code = (curl.exe -s -o NUL -w "%{http_code}" "http://127.0.0.1:$Port$Path" 2>$null)
        if ($code -eq '200') {
            return [math]::Round($sw.Elapsed.TotalSeconds, 1)
        }
        Start-Sleep -Milliseconds 300
    }
    throw "$Label 在 $TimeoutSeconds 秒内没有就绪（看 $logDir 下的日志）"
}

function Resolve-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $candidate = 'D:\Java\jdk-17.0.18\bin\java.exe'
    if (Test-Path $candidate) { return $candidate }
    return 'java'
}

# ---------- 后端：需要时先打包，再 java -jar ----------
$jar = Join-Path $root 'backend\target\ai-ticket-assistant-0.1.0.jar'
$needsBuild = $Rebuild -or -not (Test-Path $jar)
if (-not $needsBuild) {
    $jarTime = (Get-Item $jar).LastWriteTime
    $newestSource = Get-ChildItem (Join-Path $root 'backend\src') -Recurse -File -Include *.java, *.yml, *.sql |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newestSource -and $newestSource.LastWriteTime -gt $jarTime) { $needsBuild = $true }
}

$backendProc = $null
$backendSeconds = 0
if (Test-Port $BackendPort) {
    Write-Host "后端已在 $BackendPort 上运行，直接复用" -ForegroundColor DarkGray
} else {
    if ($needsBuild) {
        Write-Host '打包后端（跳过测试）…' -ForegroundColor Cyan
        Push-Location (Join-Path $root 'backend')
        try {
            & mvn.cmd -s settings.xml -o -DskipTests -q package
            if ($LASTEXITCODE -ne 0) {
                Write-Host '离线打包失败，改走在线（可能是新增了依赖）…' -ForegroundColor Yellow
                & mvn.cmd -s settings.xml -DskipTests -q package
                if ($LASTEXITCODE -ne 0) { throw '后端打包失败，看上面的 Maven 输出' }
            }
        } finally { Pop-Location }
    }

    $env:SERVER_PORT = "$BackendPort"
    $env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\Java\jdk-17.0.18' }
    Write-Host "启动后端（java -jar）… 日志: logs\backend.log" -ForegroundColor Cyan
    $backendProc = Start-Process -FilePath (Resolve-Java) `
        -ArgumentList @('-jar', $jar) `
        -WorkingDirectory (Join-Path $root 'backend') `
        -RedirectStandardOutput (Join-Path $logDir 'backend.log') `
        -RedirectStandardError (Join-Path $logDir 'backend.err.log') `
        -NoNewWindow -PassThru
    $backendSeconds = Wait-Http -Port $BackendPort -Path '/products' -TimeoutSeconds 120 -Label '后端'
    Write-Host ("后端就绪 {0}s → http://localhost:{1}" -f $backendSeconds, $BackendPort) -ForegroundColor Green
}

# ---------- 前端：Vite dev（HMR，改前端不用重启后端） ----------
$frontendProc = $null
if (Test-Port $FrontendPort) {
    Write-Host "前端已在 $FrontendPort 上运行，直接复用" -ForegroundColor DarkGray
} else {
    Write-Host "启动前端（npm run dev）… 日志: logs\frontend.log" -ForegroundColor Cyan
    $npmLog = Join-Path $logDir 'frontend.log'
    $frontendProc = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--', '--port', "$FrontendPort") `
        -WorkingDirectory (Join-Path $root 'frontend') `
        -RedirectStandardOutput $npmLog `
        -RedirectStandardError (Join-Path $logDir 'frontend.err.log') `
        -NoNewWindow -PassThru
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Port $FrontendPort)) {
        if ($sw.Elapsed.TotalSeconds -gt 60) { throw '前端 60 秒内没有起来（看 logs\frontend.log）' }
        Start-Sleep -Milliseconds 300
    }
    Write-Host ("前端就绪 {0}s → http://localhost:{1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 1), $FrontendPort) -ForegroundColor Green
}

$pids = @{ backend = if ($backendProc) { $backendProc.Id } else { $null }
           frontend = if ($frontendProc) { $frontendProc.Id } else { $null }
           backendPort = $BackendPort; frontendPort = $FrontendPort }
$pids | ConvertTo-Json | Set-Content -Path $pidFile -Encoding UTF8

$url = "http://localhost:$FrontendPort/"
Write-Host ''
Write-Host "站点已就绪：$url" -ForegroundColor Green
if (-not $NoBrowser) { Start-Process $url }
Write-Host '（改前端：Vite HMR 自动生效；改提示词：不用重启，见 /admin/prompts/reload；只有改 Java 代码需要 .\scripts\dev-up.ps1 -Rebuild）' -ForegroundColor DarkGray
