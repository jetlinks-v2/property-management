package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.payment.CashierInput;
import org.jetlinks.pro.property.finance.service.BillingJobProperties;
import org.jetlinks.pro.property.finance.service.CashierConfiguration;
import org.jetlinks.pro.property.finance.service.CashierService;
import org.jetlinks.pro.property.finance.service.PaymentRecordService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 人工收款动作只由柜台权限触发，不开放通用CRUD改流水或前端传入支付成功状态。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/cashier")
@Resource(id = "property-finance-cashier", name = "收银台")
public class CashierController {
    private final CashierService service;
    private final PaymentRecordService records;
    private final BillingJobProperties billingProperties;

    @GetMapping("/_options")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<List<CashierConfiguration.CompanyOption>> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @PostMapping("/payers/_query")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<List<CashierService.PayerOption>> payers(
            @PathVariable String projectId, @RequestBody List<String> ids) {
        return service.payerOptions(projectId, ids);
    }

    @PostMapping("/_preview")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<CashierService.Preview> preview(
            @PathVariable String projectId, @RequestBody CashierInput input) {
        return service.preview(projectId, input, businessDate());
    }

    @PostMapping("/_collect")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<CashierService.Detail> collect(
            @PathVariable String projectId, @RequestBody CashierService.Submission input) {
        return service.collect(projectId, input, businessDate());
    }

    @GetMapping("/payments/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<CashierService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping("/payments/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<PaymentRecordService.View>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return records.query(projectId, query);
    }

    /** 与算费任务使用同一业务时区，避免容器时区不同导致午夜可收额不一致。 */
    private LocalDate businessDate() {
        return LocalDate.now(ZoneId.of(billingProperties.getZone()));
    }
}
