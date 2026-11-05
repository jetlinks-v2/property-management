<#
.SYNOPSIS
使用 JetLinks 2.20 所需的 Java 17 模块开放参数启动查验服务。
.DESCRIPTION
连接参数通过进程环境或 application-local.yml 提供；脚本不保存凭据。
#>
[CmdletBinding()]
param(
    [string]$Java = 'java',
    [string]$Jar = "$PSScriptRoot/../../services/property-handover-service/target/application.jar"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "查验服务 JAR 不存在：$Jar" }
$handoverJvm = @('-Xms256m', '-Xmx1g', '-Dspring.reactor.debug-agent.enabled=false')
# 平台 FST 序列化需要访问 JDK 内部字段，与相邻业务服务使用相同参数。
foreach ($handoverPackage in @('java.lang', 'java.util', 'java.util.concurrent', 'java.io', 'java.net', 'java.text', 'java.math', 'java.time')) {
    $handoverJvm += "--add-opens=java.base/$handoverPackage=ALL-UNNAMED"
}
$handoverJvm += '--add-opens=java.scripting/javax.script=ALL-UNNAMED'
& $Java @handoverJvm '-jar' (Resolve-Path -LiteralPath $Jar).Path
exit $LASTEXITCODE
