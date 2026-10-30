$ErrorActionPreference = 'Stop'
# 只读取约定的模型变量，不执行文件内容；已显式设置的环境变量优先。
$privateEnv = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $privateEnv) {
    foreach ($line in Get-Content -LiteralPath $privateEnv) {
        if ($line -match '^(AI_API_KEY|AI_BASE_URL|AI_MODEL)=(.*)$') {
            if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($Matches[1], 'Process'))) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            }
        }
    }
}
$jar = Join-Path $PSScriptRoot 'dazao-admin\target\dazao-character-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw '请先在项目根目录运行 ./build.ps1'
}

# 数据库路径相对项目根目录，不随调用者当前目录改变。
Push-Location $PSScriptRoot
try {
    & java -jar $jar
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请查看上方日志' }
} finally {
    Pop-Location
}
