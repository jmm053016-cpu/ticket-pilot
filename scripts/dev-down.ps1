# 停掉 .\scripts\dev-up.ps1 起的两端（只动本脚本启动的进程，不碰你自己开的窗口）。
#
#   .\scripts\dev-down.ps1
param(
    [switch]$ByPort  # 兜底：按端口结束占用者（PID 文件丢了的时候用）
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs'
$pidFile = Join-Path $logDir 'dev-up.json'

function Stop-Tree([int]$ProcessId, [string]$Label) {
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "$Label 已不在运行" -ForegroundColor DarkGray
        return
    }
    # npm.cmd → cmd → node 是三层：只杀父进程会留下占着端口的 node。
    # taskkill /T 按进程树收，/F 强制（这几类进程都没有需要优雅关闭的状态）
    & taskkill.exe /PID $ProcessId /T /F 2>&1 | Out-Null
    Write-Host "$Label 已停止（PID $ProcessId，含子进程）" -ForegroundColor Green
}

if ($ByPort) {
    foreach ($port in 8080, 5173) {
        Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            ForEach-Object { Stop-Tree -ProcessId $_.OwningProcess -Label "端口 $port" }
    }
    return
}

if (-not (Test-Path $pidFile)) {
    Write-Host '没有找到 logs\dev-up.json；如果服务是 .\scripts\dev-up.ps1 起的，用 -ByPort 兜底' -ForegroundColor Yellow
    return
}

$pids = Get-Content $pidFile -Encoding UTF8 | ConvertFrom-Json
if ($pids.frontend) { Stop-Tree -ProcessId $pids.frontend -Label '前端' }
if ($pids.backend) { Stop-Tree -ProcessId $pids.backend -Label '后端' }
Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
