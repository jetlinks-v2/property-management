param(
    [string]$SaasComposeDirectory = (Join-Path $PSScriptRoot '../../cloud.jetlinks/runtime/all-in-one'),
    [string]$ComposeProject = 'jetlinks-local',
    [string]$AdvertisedHost
)

$ErrorActionPreference = 'Stop'
$composeRoot = (Resolve-Path -LiteralPath $SaasComposeDirectory).Path
$baseFile = Join-Path $composeRoot 'docker-compose.yml'
$localFile = Join-Path $composeRoot 'docker-compose.local.yml'
$overrideFile = Join-Path $PSScriptRoot 'docker-compose.host-dev.yml'
foreach ($file in @($baseFile, $localFile, $overrideFile)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Compose 文件不存在：$file"
    }
}

# 选择宿主机实际局域网地址，避免 Windows hosts 中过期的 host.docker.internal 记录。
if (-not $AdvertisedHost) {
    $addresses = @(Get-NetIPConfiguration | Where-Object {
        $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up'
    } | ForEach-Object { $_.IPv4Address.IPAddress } | Select-Object -Unique)
    if ($addresses.Count -ne 1) {
        throw '无法唯一确定宿主机地址，请通过 -AdvertisedHost 指定 Docker 与本机均可访问的地址。'
    }
    $AdvertisedHost = $addresses[0]
}
Write-Output "集群/RPC 注册地址：$AdvertisedHost"

# 保留现有数据卷和依赖容器，只更新本机开发所需的运行端注册地址与端口。
$composeArgs = @('compose', '-p', $ComposeProject, '-f', $baseFile, '-f', $localFile, '-f', $overrideFile)
$previousHost = $env:PROPERTY_DEV_HOST
try {
    $env:PROPERTY_DEV_HOST = $AdvertisedHost
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose 配置校验失败，未更新容器。' }
    & docker @composeArgs up -d --no-deps api-gateway-service all-in-one-service
    if ($LASTEXITCODE -ne 0) { throw '运行端容器更新失败，请检查 docker compose 日志。' }
    # 两侧使用同一次选择的地址；外置联调配置不修改包含密码的 application-local.yml。
    $hostConfig = Join-Path $PSScriptRoot '../config/application-host-dev.yml'
    $escapedHost = $AdvertisedHost.Replace("'", "''")
    @(
        '# 由 enable-host-dev.ps1 生成，切换网络后重新运行脚本。',
        "CLUSTER_EXTERNAL_HOST: '$escapedHost'",
        "RPC_EXTERNAL_HOST: '$escapedHost'"
    ) | Set-Content -LiteralPath $hostConfig -Encoding utf8
    & docker @composeArgs ps api-gateway-service all-in-one-service
    if ($LASTEXITCODE -ne 0) { throw '无法查询运行端容器状态。' }
} finally {
    $env:PROPERTY_DEV_HOST = $previousHost
}
