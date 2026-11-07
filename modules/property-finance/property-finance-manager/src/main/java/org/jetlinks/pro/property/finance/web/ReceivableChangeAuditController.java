package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.ReceivableChangeEntity;
import org.jetlinks.pro.property.finance.service.ReceivableChangeAuditService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** 调整与日期拆分共用本地审批入口，继续复用应收 approve/reject 按钮权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/changes")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableChangeAuditController {
    private final ReceivableChangeAuditService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ReceivableChangeEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ReceivableChangeAuditService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping("/{id}/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<ReceivableChangeEntity> approve(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableChangeAuditService.Decision input) {
        return service.decide(projectId, id, input, true, LocalDate.now());
    }

    @PostMapping("/{id}/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<ReceivableChangeEntity> reject(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableChangeAuditService.Decision input) {
        return service.decide(projectId, id, input, false, LocalDate.now());
    }
}
