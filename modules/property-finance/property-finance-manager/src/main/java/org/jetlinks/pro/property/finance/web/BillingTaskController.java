package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.entity.BillingTaskEntity;
import org.jetlinks.pro.property.finance.service.BillingTaskService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 复用收费设置菜单权限；不开放任意租户或全项目的匿名任务执行入口。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/billing-tasks")
@Resource(id = "property-finance-setting", name = "收费设置")
@Authorize
@RequiredArgsConstructor
public class BillingTaskController {
    private final BillingTaskService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<BillingTaskEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @PostMapping("/{id}/_retry")
    @ResourceAction(id = "calculate", name = "生成应收")
    public Mono<Void> retry(@PathVariable String projectId, @PathVariable String id) {
        return service.retry(projectId, id);
    }
}
