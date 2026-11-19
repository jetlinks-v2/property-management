param(
    [string]$ConfigPath = "$PSScriptRoot/../../../.agent-runtime/facility-mqtt/config.json",
    [string]$Network = 'jetlinks-local_default'
)
$ErrorActionPreference = 'Stop'
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path
$statePath = Join-Path (Split-Path $resolvedConfig) 'state'
New-Item -ItemType Directory -Force -Path $statePath | Out-Null
$existing = docker ps -aq --filter 'name=^property-facility-mqtt-simulator$'
if ($existing) { throw '模拟器容器已存在，请先用 Stop.ps1 停止，避免同一设备重复连接。' }
docker build -t property-facility-mqtt-simulator:local $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw '构建失败' }
docker run -d --name property-facility-mqtt-simulator --restart on-failure:3 --network $Network `
    --mount "type=bind,source=$resolvedConfig,target=/config/config.json,readonly" `
    --mount "type=bind,source=$statePath,target=/state" property-facility-mqtt-simulator:local
if ($LASTEXITCODE -ne 0) { throw '启动失败' }
Write-Output '已启动，普通读数每小时上报，故障变化在30秒内上报。查看日志：docker logs --tail 20 property-facility-mqtt-simulator'
