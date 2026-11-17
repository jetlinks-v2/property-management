$ErrorActionPreference = 'Stop'
$existing = docker ps -aq --filter 'name=^property-facility-mqtt-simulator$'
if ($existing) {
    docker stop -t 20 property-facility-mqtt-simulator
    if ($LASTEXITCODE -ne 0) { throw '停止失败' }
    docker rm property-facility-mqtt-simulator
    if ($LASTEXITCODE -ne 0) { throw '移除容器失败' }
}
Write-Output '已停止上报，累计读数保留在配置文件旁的 state 目录。'
