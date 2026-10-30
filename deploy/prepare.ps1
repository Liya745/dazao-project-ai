$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$web = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'artifacts\web'))
if ($web -ne (Join-Path $projectRoot 'deploy\artifacts\web')) {
    throw '部署构建输出不在约定的项目目录'
}

# 部署构建与本地根路径构建分开，避免覆盖开发用页面及 Jar。
Push-Location (Join-Path $projectRoot 'dazao-ui')
$previousBase = $env:VITE_BASE_PATH
try {
    $env:VITE_BASE_PATH = '/dzproject/'
    & npm.cmd run build -- --outDir $web --emptyOutDir
    if ($LASTEXITCODE -ne 0) { throw '部署前端构建失败' }
} finally {
    $env:VITE_BASE_PATH = $previousBase
    Pop-Location
}
Push-Location $projectRoot
try {
    & mvn.cmd -B -ntp -Pdeployment clean package
    if ($LASTEXITCODE -ne 0) { throw '部署 Jar 构建或测试失败' }
    $application = Join-Path $projectRoot 'dazao-admin\target\deployment\dazao-character-0.1.0-SNAPSHOT.jar'
    New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'artifacts\app') -Force | Out-Null
    Copy-Item -LiteralPath $application -Destination (Join-Path $PSScriptRoot 'artifacts\app\application.jar')
} finally {
    Pop-Location
}
