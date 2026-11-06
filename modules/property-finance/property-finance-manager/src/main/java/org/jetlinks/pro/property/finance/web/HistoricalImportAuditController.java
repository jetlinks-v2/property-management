package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.ReceivableImportEntity;
import org.jetlinks.pro.property.finance.service.HistoricalImportAuditService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 导入审核复用应收菜单动作；服务层继续校验项目和每条房屋资产权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/imports")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class HistoricalImportAuditController {
    private final HistoricalImportAuditService service;

    @PostMapping("/{recordId}/lines/_query")
    @ResourceAction(id = "imports", name = "导入记录")
    public Mono<PagerResult<HistoricalImportAuditService.LineView>> lines(
            @PathVariable String projectId,
            @PathVariable String recordId,
            @RequestBody QueryParamEntity query) {
        return service.lines(projectId, recordId, query);
    }

    @PostMapping("/{recordId}/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<ReceivableImportEntity> approve(
            @PathVariable String projectId,
            @PathVariable String recordId,
            @RequestBody HistoricalImportAuditService.Decision input) {
        return service.decide(projectId, recordId, input, true);
    }

    @PostMapping("/{recordId}/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<ReceivableImportEntity> reject(
            @PathVariable String projectId,
            @PathVariable String recordId,
            @RequestBody HistoricalImportAuditService.Decision input) {
        return service.decide(projectId, recordId, input, false);
    }

    @PostMapping("/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<java.util.List<ReceivableImportEntity>> approveBatches(
            @PathVariable String projectId,
            @RequestBody HistoricalImportAuditService.BatchDecision input) {
        return service.decideBatches(projectId, input, true);
    }

    @PostMapping("/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<java.util.List<ReceivableImportEntity>> rejectBatches(
            @PathVariable String projectId,
            @RequestBody HistoricalImportAuditService.BatchDecision input) {
        return service.decideBatches(projectId, input, false);
    }
}
