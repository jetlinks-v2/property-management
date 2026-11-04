param(
    [Parameter(Mandatory = $true)]
    [string]$SaasComposeDirectory,
    [ValidateSet('base', 'finance', 'patrol')]
    [string]$System = 'patrol',
    [string]$ComposeProject = 'jetlinks-local',
    [string]$AdvertisedHost
)

$ErrorActionPreference = 'Stop'
$composeRoot = (Resolve-Path -LiteralPath $SaasComposeDirectory).Path
$baseFile = Join-Path $composeRoot 'docker-compose.yml'
$localFile = Join-Path $composeRoot 'docker-compose.local.yml'
$overrideFile = Join-Path $PSScriptRoot 'docker-compose.host-dev.yml'
$localConfigFile = Join-Path $PSScriptRoot "../services/property-$System-service/src/main/resources/application-local.yml"
foreach ($file in @($baseFile, $localFile, $overrideFile)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Compose 文件不存在：$file"
    }
}
if (-not (Test-Path -LiteralPath $localConfigFile -PathType Leaf)) {
    throw '请先参考启动服务 resources/application-default.yml 创建 application-local.yml，填写本机连接配置。'
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

# 本地配置使用根级连接变量；只替换这两个键，保留其他设置、注释及密钥原文。
$localConfig = [System.IO.File]::ReadAllText($localConfigFile)
$escapedHost = $AdvertisedHost.Replace("'", "''")
foreach ($key in @('CLUSTER_EXTERNAL_HOST', 'RPC_EXTERNAL_HOST')) {
    $pattern = "(?m)^${key}:[^\r\n]*"
    $matchesForKey = [regex]::Matches($localConfig, $pattern)
    if ($matchesForKey.Count -gt 1) {
        throw "application-local.yml 中存在重复的 $key，请先保留唯一配置。"
    }
    $replacement = "${key}: '$escapedHost'"
    if ($matchesForKey.Count -eq 1) {
        $localConfig = [regex]::Replace($localConfig, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $replacement })
    } else {
        $localConfig += "`n$replacement`n"
    }
}

# 保留现有数据卷和依赖容器，只更新本机开发所需的运行端注册地址与端口。
$composeArgs = @('compose', '-p', $ComposeProject, '-f', $baseFile, '-f', $localFile, '-f', $overrideFile)
$previousHost = $env:PROPERTY_DEV_HOST
try {
    $env:PROPERTY_DEV_HOST = $AdvertisedHost
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose 配置校验失败，未更新容器。' }
    & docker @composeArgs up -d --no-deps api-gateway-service all-in-one-service
    if ($LASTEXITCODE -ne 0) { throw '运行端容器更新失败，请检查 docker compose 日志。' }
    [System.IO.File]::WriteAllText($localConfigFile, $localConfig, [System.Text.UTF8Encoding]::new($false))
    & docker @composeArgs ps api-gateway-service all-in-one-service
    if ($LASTEXITCODE -ne 0) { throw '无法查询运行端容器状态。' }
} finally {
    $env:PROPERTY_DEV_HOST = $previousHost
}
