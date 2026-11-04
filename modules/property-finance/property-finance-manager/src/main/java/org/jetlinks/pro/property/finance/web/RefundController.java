package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.payment.RefundInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

/** 退款仅经业务确认入口，权限与收银分离，无通用更新或删除入口。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/refunds")
@Resource(id = "property-finance-refund", name = "退款管理")
public class RefundController {
    private final RefundService service;
    private final RefundRecordService records;
    private final BillingJobProperties properties;

    @GetMapping("/_options")
    @ResourceAction(id = "refund", name = "退款")
    public Mono<List<CashierConfiguration.CompanyOption>> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @PostMapping("/_preview")
    @ResourceAction(id = "refund", name = "退款")
    public Mono<RefundService.Preview> preview(
            @PathVariable String projectId, @RequestBody RefundInput input) {
        return service.preview(projectId, input, date());
    }

    @PostMapping("/sources/_query")
    @ResourceAction(id = "refund", name = "退款")
    public Mono<List<RefundSourceService.Option>> sources(
            @PathVariable String projectId, @RequestBody List<String> ids) {
        return service.sourceOptions(projectId, ids);
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "refund", name = "退款")
    public Mono<RefundService.Detail> confirm(
            @PathVariable String projectId, @RequestBody RefundService.Submission input) {
        return service.confirm(projectId, input, date());
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<RefundRecordService.View>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return records.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<RefundService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}
