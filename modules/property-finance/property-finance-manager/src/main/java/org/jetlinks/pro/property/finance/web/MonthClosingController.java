package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.MonthClosingMemberEntity;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 月结/反结和规则分别声明按钮权限；实际业务日只由服务端业务时区取得。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/month-closing")
@Resource(id = "property-finance-month-closing", name = "财务月结")
public class MonthClosingController {
    private final MonthClosingService service;
    private final MonthClosingBatchService batchService;
    private final MonthClosingRuleService rules;
    private final MonthClosingRecords records;
    private final BillingJobProperties properties;

    @PostMapping("/batch/_close/preview")
    @ResourceAction(id = "close", name = "月结")
    public Mono<java.util.List<MonthClosingBatchService.Row>> previewBatchClose(
            @PathVariable String projectId, @RequestBody MonthClosingBatchService.Input input) {
        return batchService.preview(projectId, "CLOSE", input, today());
    }

    @PostMapping("/batch/_close")
    @ResourceAction(id = "close", name = "月结")
    public Mono<java.util.List<MonthClosingBatchService.Row>> batchClose(
            @PathVariable String projectId, @RequestBody MonthClosingBatchService.Input input) {
        return batchService.execute(projectId, "CLOSE", input, today());
    }

    @PostMapping("/batch/_reopen/preview")
    @ResourceAction(id = "reopen", name = "反月结")
    public Mono<java.util.List<MonthClosingBatchService.Row>> previewBatchReopen(
            @PathVariable String projectId, @RequestBody MonthClosingBatchService.Input input) {
        return batchService.preview(projectId, "REOPEN", input, today());
    }

    @PostMapping("/batch/_reopen")
    @ResourceAction(id = "reopen", name = "反月结")
    public Mono<java.util.List<MonthClosingBatchService.Row>> batchReopen(
            @PathVariable String projectId, @RequestBody MonthClosingBatchService.Input input) {
        return batchService.execute(projectId, "REOPEN", input, today());
    }

    @GetMapping("/rule")
    @QueryAction
    public Mono<MonthClosingRuleService.View> rule(@PathVariable String projectId) {
        return rules.get(projectId);
    }

    @PutMapping("/rule")
    @ResourceAction(id = "save", name = "月结规则")
    public Mono<MonthClosingRuleService.View> rule(
            @PathVariable String projectId, @RequestBody MonthClosingRuleService.Input input) {
        return rules.save(projectId, input);
    }

    @PostMapping("/{month}/_close/preview")
    @ResourceAction(id = "close", name = "月结")
    public Mono<MonthClosingService.Preview> previewClose(
            @PathVariable String projectId, @PathVariable String month) {
        return service.preview(projectId, month, "CLOSE", today());
    }

    @PostMapping("/{month}/_close")
    @ResourceAction(id = "close", name = "月结")
    public Mono<MonthClosingService.Result> close(
            @PathVariable String projectId,
            @PathVariable String month,
            @RequestBody MonthClosingService.Submission input) {
        return service.submit(projectId, month, "CLOSE", input, today());
    }

    @PostMapping("/{month}/_reopen/preview")
    @ResourceAction(id = "reopen", name = "反月结")
    public Mono<MonthClosingService.Preview> previewReopen(
            @PathVariable String projectId, @PathVariable String month) {
        return service.preview(projectId, month, "REOPEN", today());
    }

    @PostMapping("/{month}/_reopen")
    @ResourceAction(id = "reopen", name = "反月结")
    public Mono<MonthClosingService.Result> reopen(
            @PathVariable String projectId,
            @PathVariable String month,
            @RequestBody MonthClosingService.Submission input) {
        return service.submit(projectId, month, "REOPEN", input, today());
    }

    @PostMapping("/{month}/logs/_query")
    @QueryAction
    public Mono<PagerResult<MonthClosingRecords.Detail>> query(
            @PathVariable String projectId,
            @PathVariable String month,
            @RequestBody QueryParamEntity query) {
        return records.query(projectId, month, query);
    }

    @GetMapping("/operations/{id}")
    @QueryAction
    public Mono<MonthClosingRecords.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return records.detail(projectId, id);
    }

    @PostMapping("/operations/{id}/members/_query")
    @QueryAction
    public Mono<PagerResult<MonthClosingMemberEntity>> members(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity query) {
        return records.members(projectId, id, query);
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}
