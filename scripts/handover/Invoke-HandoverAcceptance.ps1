<#
.SYNOPSIS
通过真实 SaaS 网关验证查验契约。只读检查默认开启；-Mutation 在显式指定的测试项目中创建验收记录。
.DESCRIPTION
令牌只从进程环境 HANDOVER_TEST_TOKEN 读取，不写入报告。测试数据遵循业务逻辑删除，保留审计。
执行前启动基础服务和查验服务，初始化应用及动作权限，准备具有员工身份的测试账号。
#>
[CmdletBinding()]
param(
    [string]$Gateway = 'http://localhost:8800',
    [string]$ApiPrefix = '',
    [Parameter(Mandatory)][string]$TenantDomain,
    [Parameter(Mandatory)][string]$ApplicationId,
    [Parameter(Mandatory)][string]$ProjectId,
    [switch]$Mutation
)

$ErrorActionPreference = 'Stop'
if (-not $env:HANDOVER_TEST_TOKEN) { throw '请通过进程环境 HANDOVER_TEST_TOKEN 配置测试令牌。' }
$handoverHeaders = @{
    'X-Access-Token' = $env:HANDOVER_TEST_TOKEN
    'X-Tenant-Domain' = $TenantDomain
    'X-Asset-Scope' = "business_application:$ApplicationId"
}
# 直连 SaaS 网关不带 /api；通过前端代理时显式指定 -ApiPrefix /api。
$handoverBase = $Gateway.TrimEnd('/') + '/' + (($ApiPrefix.Trim('/') + '/property/handover').TrimStart('/'))
$handoverChecks = [System.Collections.Generic.List[object]]::new()

function Invoke-Handover([string]$Method, [string]$Path, $Body = $null, [switch]$Anonymous) {
    $request = @{ Method = $Method; Uri = "$handoverBase$Path"; SkipHttpErrorCheck = $true }
    if (-not $Anonymous) { $request.Headers = $handoverHeaders }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    $response = Invoke-WebRequest @request
    $payload = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    return @{ Status = [int]$response.StatusCode; Payload = $payload; Result = $payload.result }
}

function Assert-Contract([bool]$Condition, [string]$Name) {
    $handoverChecks.Add(@{ name = $Name; passed = $Condition })
    if (-not $Condition) { throw "验收未通过：$Name" }
    Write-Host "通过：$Name"
}

try {
    $denied = Invoke-Handover GET '/directory/projects' -Anonymous
    Assert-Contract ($denied.Status -in 401, 403) '匿名调用无法获取项目目录'
    $projects = Invoke-Handover GET '/directory/projects'
    Assert-Contract ($projects.Status -eq 200) '项目目录经 SaaS 网关调用成功'
    Assert-Contract (@($projects.Result | Where-Object id -eq $ProjectId).Count -eq 1) '验收项目存在于当前账号授权目录'
    $projectQuery = '?projectId=' + [Uri]::EscapeDataString($ProjectId)
    $page = Invoke-Handover GET "/problem/_query$projectQuery&pageIndex=0&pageSize=2"
    Assert-Contract ($page.Status -eq 200 -and $page.Result.pageSize -eq 2 -and $page.Result.total -ge 0) '问题接口返回真实分页与总数'
    $forged = Invoke-Handover GET '/problem/_query?projectId=00000000-0000-0000-0000-000000000000'
    Assert-Contract ($forged.Status -in 403, 404) '伪造项目不能查询问题'
    $statistics = Invoke-Handover GET "/problem/_counts$projectQuery"
    Assert-Contract ($statistics.Status -eq 200 -and $statistics.Result.total -eq $page.Result.total) '同条件计数与分页总数一致'
    $process = Invoke-Handover GET "/process$projectQuery"
    Assert-Contract ($process.Status -eq 200 -and @($process.Result.stages).Count -eq 8) '项目包含八个可辨识阶段'

    if ($Mutation) {
        $requestId = 'acceptance-' + [Guid]::NewGuid().ToString('N')
        $body = @{
            projectId = $ProjectId; source = 'SITE_INSPECTION'; clientRequestId = $requestId
            title = '自动验收记录'; description = '幂等与审核状态验收'; location = '验收测试位置'; photos = @()
        }
        $created = Invoke-Handover POST '/problem' $body
        Assert-Contract ($created.Status -eq 200 -and $created.Result.id) '创建查验问题'
        $again = Invoke-Handover POST '/problem' $body
        Assert-Contract ($again.Status -eq 200 -and $again.Result.id -eq $created.Result.id) '成功回包丢失后以同请求编号重试不重复创建'
        $problem = $created.Result
        $invalidReview = Invoke-Handover POST "/problem/$($problem.id)/final-review" @{ version = $problem.version; passed = $true }
        Assert-Contract ($invalidReview.Status -eq 409) '未经初审的问题不能直接终审'
        $invalidReject = Invoke-Handover POST "/problem/$($problem.id)/initial-review" @{ version = $problem.version; passed = $false; reason = '' }
        Assert-Contract ($invalidReject.Status -in 400, 409) '空退回原因不能改变状态'
        $beforeDelete = Invoke-Handover GET "/problem/$($problem.id)"
        Assert-Contract ($beforeDelete.Result.version -eq $problem.version) '拒绝的操作没有修改版本'
        $deleted = Invoke-Handover DELETE "/problem/$($problem.id)?version=$($problem.version)"
        Assert-Contract ($deleted.Status -eq 200) '误建问题通过业务接口逻辑删除'
        $afterDelete = Invoke-Handover GET "/problem/$($problem.id)"
        Assert-Contract ($afterDelete.Status -in 403, 404) '普通详情不能读取已删除问题'
    }
} finally {
    [pscustomobject]@{ target = $Gateway; mutation = [bool]$Mutation; checks = $handoverChecks } | ConvertTo-Json -Depth 8
}
