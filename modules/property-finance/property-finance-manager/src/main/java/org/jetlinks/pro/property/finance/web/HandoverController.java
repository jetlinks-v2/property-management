package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 交账及按钮复核独立授权，不接入工作流。所有操作由显式项目和空间权限约束。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/handovers")
@Resource(id = "property-finance-handovers", name = "交账管理")
public class HandoverController {
    private final HandoverService service;
    private final HandoverRecords records;
    private final HandoverBatchService batchService;
    private final HandoverStatementService statements;
    private final BillingJobProperties properties;

    @GetMapping("/{id}/statement")
    @QueryAction
    public Mono<HandoverStatementService.Statement> statement(
            @PathVariable String projectId, @PathVariable String id) {
        return statements.statement(projectId, id);
    }

    @GetMapping("/{id}/statement/{format}")
    @QueryAction
    public Mono<org.springframework.http.ResponseEntity<byte[]>> statementFile(
            @PathVariable String projectId, @PathVariable String id, @PathVariable String format) {
        return statements
                .export(projectId, id, format)
                .map(
                        bytes ->
                                org.springframework.http.ResponseEntity.ok()
                                        .contentType(
                                                org.springframework.http.MediaType.parseMediaType(
                                                        "pdf".equals(format)
                                                                ? "application/pdf"
                                                                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                "Content-Disposition",
                                                "attachment; filename=handover."
                                                        + ("pdf".equals(format) ? "pdf" : "xlsx"))
                                        .body(bytes));
    }

    @PostMapping("/details/_export/{format}")
    @QueryAction
    public Mono<org.springframework.http.ResponseEntity<byte[]>> exportDetails(
            @PathVariable String projectId,
            @PathVariable String format,
            @RequestBody HandoverRecords.DetailQuery input) {
        return statements
                .exportDetails(projectId, input, format)
                .map(
                        bytes ->
                                org.springframework.http.ResponseEntity.ok()
                                        .contentType(
                                                org.springframework.http.MediaType.parseMediaType(
                                                        "pdf".equals(format)
                                                                ? "application/pdf"
                                                                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                "Content-Disposition",
                                                "attachment; filename=handover-details."
                                                        + ("pdf".equals(format) ? "pdf" : "xlsx"))
                                        .body(bytes));
    }

    @PostMapping("/details/_query")
    @QueryAction
    public Mono<PagerResult<HandoverRecords.DetailRow>> details(
            @PathVariable String projectId, @RequestBody HandoverRecords.DetailQuery input) {
        return records.details(projectId, input);
    }

    @PostMapping("/batch/_approve/preview")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<java.util.List<HandoverBatchService.Result>> previewApprove(
            @PathVariable String projectId, @RequestBody HandoverBatchService.Input input) {
        return batchService.preview(projectId, "APPROVE", input);
    }

    @PostMapping("/batch/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<java.util.List<HandoverBatchService.Result>> batchApprove(
            @PathVariable String projectId, @RequestBody HandoverBatchService.Input input) {
        return batchService.execute(projectId, "APPROVE", input, now());
    }

    @PostMapping("/batch/_unapprove/preview")
    @ResourceAction(id = "unapprove", name = "反审核")
    public Mono<java.util.List<HandoverBatchService.Result>> previewUnapprove(
            @PathVariable String projectId, @RequestBody HandoverBatchService.Input input) {
        return batchService.preview(projectId, "UNAPPROVE", input);
    }

    @PostMapping("/batch/_unapprove")
    @ResourceAction(id = "unapprove", name = "反审核")
    public Mono<java.util.List<HandoverBatchService.Result>> batchUnapprove(
            @PathVariable String projectId, @RequestBody HandoverBatchService.Input input) {
        return batchService.execute(projectId, "UNAPPROVE", input, now());
    }

    @PostMapping("/sources/{type}/_query")
    @ResourceAction(id = "create", name = "交账")
    public Mono<PagerResult<HandoverRecords.Candidate>> sources(
            @PathVariable String projectId,
            @PathVariable String type,
            @RequestBody QueryParamEntity input) {
        return records.candidates(projectId, type, input);
    }

    @PostMapping("/_preview")
    @ResourceAction(id = "create", name = "交账")
    public Mono<HandoverService.Preview> preview(
            @PathVariable String projectId, @RequestBody HandoverService.Input input) {
        return service.preview(projectId, input, now());
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "create", name = "交账")
    public Mono<HandoverRecords.Detail> confirm(
            @PathVariable String projectId, @RequestBody HandoverService.Submission input) {
        return service.confirm(projectId, input, now());
    }

    @PostMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return records.query(projectId, input);
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverRecords.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return records.detail(projectId, id);
    }

    @PostMapping("/{id}/logs/_query")
    @QueryAction
    public Mono<PagerResult<HandoverOperationEntity>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return records.logs(projectId, id, input);
    }

    @PostMapping("/{id}/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<HandoverRecords.Detail> approve(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody HandoverService.Action input) {
        return service.action(projectId, id, "APPROVE", input, now());
    }

    @PostMapping("/{id}/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<HandoverRecords.Detail> reject(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody HandoverService.Action input) {
        return service.action(projectId, id, "REJECT", input, now());
    }

    @PostMapping("/{id}/_unapprove")
    @ResourceAction(id = "unapprove", name = "反审核")
    public Mono<HandoverRecords.Detail> unapprove(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody HandoverService.Action input) {
        return service.action(projectId, id, "UNAPPROVE", input, now());
    }

    @PostMapping("/{id}/_cancel")
    @ResourceAction(id = "cancel", name = "反交账")
    public Mono<HandoverRecords.Detail> cancel(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody HandoverService.Action input) {
        return service.action(projectId, id, "CANCEL", input, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(properties.getZone()));
    }
}
