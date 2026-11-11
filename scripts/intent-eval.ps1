<#
.SYNOPSIS
  意图/动作评测：逐条跑 /assistant/stream，核对实际调用的工具是否等于期望值。

.DESCRIPTION
  用来对比"关键词优先（legacy）"与"模型优先（model-first）"两种决策模式。
  同一份语料跑两遍，看动作正确率 —— 这就是改判定顺序前先要拿到的数字。

.EXAMPLE
  .\scripts\intent-eval.ps1 -BaseUrl http://localhost:18080 -Label model-first
  .\scripts\intent-eval.ps1 -BaseUrl http://localhost:18080 -Label legacy -ShowMismatch
#>
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$Corpus = '',
    [string]$Label = 'eval',
    [string]$CustomerId = 'CUST-1001',
    [switch]$ShowMismatch
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Corpus)) {
    $Corpus = Join-Path $root 'backend\src\test\resources\intent-eval-corpus.tsv'
}
if (-not (Test-Path $Corpus)) { throw "找不到语料文件: $Corpus" }

# 1) 登录拿令牌（鉴权开着时必需；关闭时这步失败也不致命）
$script:token = ''
try {
    $bodyFile = Join-Path $env:TEMP 'intent-eval-login.json'
    [System.IO.File]::WriteAllText($bodyFile, ('{"customerId":"' + $CustomerId + '"}'), (New-Object System.Text.UTF8Encoding($false)))
    $login = curl.exe -s -m 10 -X POST -H 'Content-Type:application/json' --data-binary "@$bodyFile" "$BaseUrl/auth/login"
    Remove-Item $bodyFile -Force -ErrorAction SilentlyContinue
    $script:token = ($login | ConvertFrom-Json).token
} catch {
    $script:token = ''
}

function Invoke-EvalStream {
    param([string]$Message, [string]$SessionId)
    $url = "$BaseUrl/assistant/stream?message=$([uri]::EscapeDataString($Message))&customerId=$CustomerId&sessionId=$SessionId"
    if ($script:token) { $url = "$url&token=$script:token" }
    $raw = curl.exe -s -N -m 120 $url
    $events = @()
    foreach ($line in $raw) {
        if ($line -like 'data:*') {
            try { $events += ($line.Substring(5) | ConvertFrom-Json) } catch { }
        }
    }
    $tools = @()
    foreach ($event in ($events | Where-Object { $_.type -eq 'tool_call' })) {
        foreach ($call in $event.payload.toolCalls) { $tools += $call.tool }
    }
    return ($tools | Sort-Object -Unique)
}

$rows = Get-Content $Corpus -Encoding UTF8 | Where-Object { $_.Trim() -ne '' -and -not $_.TrimStart().StartsWith('#') }
$total = 0
$passed = 0
$failures = @()
$sessionId = 'eval-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

foreach ($row in $rows) {
    $parts = $row -split "`t"
    if ($parts.Count -lt 2) { continue }
    $message = $parts[0].Trim()
    $expectedRaw = $parts[1].Trim()
    $expected = if ($expectedRaw -eq 'NONE') { @() } else { $expectedRaw -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ } }

    $actual = Invoke-EvalStream -Message $message -SessionId $sessionId
    $total++

    $same = (($expected | Sort-Object) -join ',') -eq (($actual | Sort-Object) -join ',')
    if ($same) {
        $passed++
        Write-Host ("  ✓ " + $message) -ForegroundColor Green
    } else {
        $exp = if ($expected.Count -eq 0) { 'NONE' } else { $expected -join ',' }
        $act = if ($actual.Count -eq 0) { 'NONE' } else { $actual -join ',' }
        Write-Host ("  ✗ " + $message) -ForegroundColor Yellow
        Write-Host ("      期望: $exp   实际: $act") -ForegroundColor Yellow
        $failures += [pscustomobject]@{ Message = $message; Expected = $exp; Actual = $act }
    }
}

$rate = if ($total -gt 0) { [math]::Round(100.0 * $passed / $total, 1) } else { 0 }
Write-Host ""
Write-Host ("[$Label] 动作正确率: $passed/$total = $rate%") -ForegroundColor Cyan
if ($ShowMismatch -and $failures.Count -gt 0) {
    Write-Host "`n不一致清单:" -ForegroundColor Cyan
    $failures | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
}
if ($total -eq 0) { exit 2 }
