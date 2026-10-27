$ErrorActionPreference = 'Stop'

# 从脚本目录构建，避免在别的工作目录写入依赖和产物。
Push-Location $PSScriptRoot
try {
    Push-Location (Join-Path $PSScriptRoot 'frontend')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally {
        Pop-Location
    }
    & mvn.cmd -B -ntp clean package
    if ($LASTEXITCODE -ne 0) { throw '后端测试或打包失败' }
} finally {
    Pop-Location
}
