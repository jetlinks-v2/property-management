package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.CarryForwardRunAccessService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 执行日志只读，失败重试使用独立按钮权限；请求不能提供执行身份、业务日期或金额。 */
@RestController
@Authorize
@Resource(id = "property-finance-carry", name = "自动结转")
@RequestMapping("/property/finance/projects/{projectId}/carry-plans/{planId}/runs")
@RequiredArgsConstructor
public class CarryForwardRunController {
    private final CarryForwardRunAccessService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<CarryForwardRunEntity>> query(
            @PathVariable String projectId,
            @PathVariable String planId,
            @RequestBody QueryParamEntity input) {
        return service.query(projectId, planId, input);
    }

    @GetMapping("/{runId}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<CarryForwardRunEntity> detail(
            @PathVariable String projectId,
            @PathVariable String planId,
            @PathVariable String runId) {
        return service.detail(projectId, planId, runId);
    }

    @PostMapping("/{runId}/works/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<CarryForwardWorkEntity>> works(
            @PathVariable String projectId,
            @PathVariable String planId,
            @PathVariable String runId,
            @RequestBody QueryParamEntity input) {
        return service.works(projectId, planId, runId, input);
    }

    @GetMapping("/{runId}/works/{workId}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<CarryForwardWorkEntity> work(
            @PathVariable String projectId,
            @PathVariable String planId,
            @PathVariable String runId,
            @PathVariable String workId) {
        return service.work(projectId, planId, runId, workId);
    }

    @PostMapping("/{runId}/works/{workId}/items/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<CarryForwardItemEntity>> items(
            @PathVariable String projectId,
            @PathVariable String planId,
            @PathVariable String runId,
            @PathVariable String workId,
            @RequestBody QueryParamEntity input) {
        return service.items(projectId, planId, runId, workId, input);
    }

    @PostMapping("/{runId}/_retry")
    @ResourceAction(id = "retry", name = "失败重试")
    public Mono<CarryForwardRunEntity> retry(
            @PathVariable String projectId,
            @PathVariable String planId,
            @PathVariable String runId) {
        return service.retry(projectId, planId, runId);
    }
}
