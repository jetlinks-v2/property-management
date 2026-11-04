package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.payment.PaymentToAdvanceInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/** 转预存选项使用自身动作权限，不要求授予其他资金菜单的维护权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/payment-to-advance")
@Resource(id = "property-finance-payment", name = "实收管理")
public class PaymentToAdvanceController {
    private final PaymentToAdvanceService service;
    private final RefundService refunds;
    private final HouseSettingService settings;
    private final CashierService cashier;
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<PaymentToAdvanceService.Preview> preview(
            @PathVariable String projectId, @RequestBody PaymentToAdvanceInput input) {
        return service.preview(projectId, input, date());
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<FundsService.Result> confirm(
            @PathVariable String projectId, @RequestBody PaymentToAdvanceService.Submission input) {
        return service.confirm(projectId, input, date());
    }

    @PostMapping("/sources")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<List<RefundSourceService.Option>> sources(
            @PathVariable String projectId, @RequestBody List<String> ids) {
        return refunds.sourceOptions(projectId, ids);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return Mono.zip(settings.options(projectId), cashier.options(projectId))
                .map(
                        v -> {
                            var options = new HashMap<String, Object>(v.getT1());
                            options.put("companies", v.getT2());
                            return options;
                        });
    }

    @PostMapping("/houses/_query")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<PropertyHousePage> houses(
            @PathVariable String projectId, @RequestBody FundsController.HouseQuery q) {
        return settings.houses(projectId, null, q.keyword(), q.pageIndex(), q.pageSize());
    }

    @GetMapping("/houses/{id}")
    @ResourceAction(id = "to-advance", name = "实收转预存")
    public Mono<PropertyHouseContext> house(
            @PathVariable String projectId, @PathVariable String id) {
        return settings.houseContext(projectId, id);
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}
