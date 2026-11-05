package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.FundsInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/funds")
@Resource(id = "property-finance-funds", name = "预收与押金")
public class FundsController {
    private final ReceivableService receivables;
    private final FundsService service;
    private final HouseSettingService settings;
    private final CashierService cashier;
    private final BillingJobProperties properties;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<FundAccountEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity q) {
        return service.query(projectId, q);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<FundAccountEntity> detail(@PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping("/{id}/entries/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<FundEntryEntity>> history(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity q) {
        return service.history(projectId, id, q);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return Mono.zip(settings.options(projectId), cashier.options(projectId))
                .map(
                        v -> {
                            var m = new HashMap<String, Object>(v.getT1());
                            m.put("companies", v.getT2());
                            return m;
                        });
    }

    @PostMapping("/houses/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PropertyHousePage> houses(
            @PathVariable String projectId, @RequestBody HouseQuery q) {
        return settings.houses(projectId, null, q.keyword(), q.pageIndex(), q.pageSize());
    }

    @GetMapping("/houses/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PropertyHouseContext> house(
            @PathVariable String projectId, @PathVariable String id) {
        return settings.houseContext(projectId, id);
    }

    @PostMapping("/{id}/bills/_query")
    @ResourceAction(id = "offset", name = "结转")
    public Mono<PagerResult<org.jetlinks.pro.property.finance.receivable.ReceivableView>> bills(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity q) {
        return service.detail(projectId, id)
                .flatMap(
                        a -> {
                            q.toNestQuery(
                                    n ->
                                            n.and("spaceId", a.getSpaceId())
                                                    .and("subjectId", a.getSubjectId())
                                                    .gt("arrears", 0));
                            return receivables.query(projectId, q);
                        });
    }

    @PostMapping("/quote")
    @ResourceAction(id = "query", name = "查询")
    public Mono<String> quote(@PathVariable String projectId, @RequestBody Quote q) {
        return service.quote(projectId, q.standardId(), q.companyId(), q.quantity());
    }

    public record Quote(String standardId, String companyId, java.math.BigDecimal quantity) {}

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }

    private void action(String action, FundsInput input) {
        if (input == null || !action.equals(input.getAction()))
            throw FinanceScope.error("fund_invalid", 400);
    }

    public record HouseQuery(String keyword, int pageIndex, int pageSize) {}

    @PostMapping("/topup/_preview")
    @ResourceAction(id = "topup", name = "收取")
    public Mono<FundsService.Preview> topupPreview(
            @PathVariable String projectId, @RequestBody FundsInput input) {
        action("TOPUP", input);
        return service.preview(projectId, input, date());
    }

    @PostMapping("/topup/_confirm")
    @ResourceAction(id = "topup", name = "收取")
    public Mono<FundsService.Result> topupConfirm(
            @PathVariable String projectId, @RequestBody FundsService.Submission input) {
        action("TOPUP", input.input());
        return service.execute(projectId, input, date());
    }

    @PostMapping("/refund/_preview")
    @ResourceAction(id = "refund", name = "退还")
    public Mono<FundsService.Preview> refundPreview(
            @PathVariable String projectId, @RequestBody FundsInput input) {
        action("REFUND", input);
        return service.preview(projectId, input, date());
    }

    @PostMapping("/refund/_confirm")
    @ResourceAction(id = "refund", name = "退还")
    public Mono<FundsService.Result> refundConfirm(
            @PathVariable String projectId, @RequestBody FundsService.Submission input) {
        action("REFUND", input.input());
        return service.execute(projectId, input, date());
    }

    @PostMapping("/transfer/_preview")
    @ResourceAction(id = "transfer", name = "划转")
    public Mono<FundsService.Preview> transferPreview(
            @PathVariable String projectId, @RequestBody FundsInput input) {
        action("TRANSFER", input);
        return service.preview(projectId, input, date());
    }

    @PostMapping("/transfer/_confirm")
    @ResourceAction(id = "transfer", name = "划转")
    public Mono<FundsService.Result> transferConfirm(
            @PathVariable String projectId, @RequestBody FundsService.Submission input) {
        action("TRANSFER", input.input());
        return service.execute(projectId, input, date());
    }

    @PostMapping("/deduct/_preview")
    @ResourceAction(id = "deduct", name = "扣除")
    public Mono<FundsService.Preview> deductPreview(
            @PathVariable String projectId, @RequestBody FundsInput input) {
        action("DEDUCT", input);
        return service.preview(projectId, input, date());
    }

    @PostMapping("/deduct/_confirm")
    @ResourceAction(id = "deduct", name = "扣除")
    public Mono<FundsService.Result> deductConfirm(
            @PathVariable String projectId, @RequestBody FundsService.Submission input) {
        action("DEDUCT", input.input());
        return service.execute(projectId, input, date());
    }

    @PostMapping("/offset/_preview")
    @ResourceAction(id = "offset", name = "结转")
    public Mono<FundsService.Preview> offsetPreview(
            @PathVariable String projectId, @RequestBody FundsInput input) {
        action("OFFSET", input);
        return service.preview(projectId, input, date());
    }

    @PostMapping("/offset/_confirm")
    @ResourceAction(id = "offset", name = "结转")
    public Mono<FundsService.Result> offsetConfirm(
            @PathVariable String projectId, @RequestBody FundsService.Submission input) {
        action("OFFSET", input.input());
        return service.execute(projectId, input, date());
    }
}
