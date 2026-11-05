package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.payment.TemporaryInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/** 临时款操作独立授权，收银台仅提供入口；查询或保存均校验项目及房屋权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/temporary")
@Resource(id = "property-finance-temporary", name = "临时收款")
public class TemporaryController {
    private final TemporaryService service;
    private final HouseSettingService settings;
    private final CashierService cashier;
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<TemporaryService.Preview> preview(
            @PathVariable String projectId, @RequestBody TemporaryInput input) {
        return service.preview(projectId, input, date());
    }

    @PostMapping("/_collect")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<TemporaryService.Detail> collect(
            @PathVariable String projectId, @RequestBody TemporaryService.Submission input) {
        return service.collect(projectId, input, date());
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<PaymentRecordService.View>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<TemporaryService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return Mono.zip(settings.options(projectId), cashier.options(projectId))
                .map(
                        values -> {
                            var options = new HashMap<String, Object>(values.getT1());
                            options.put("companies", values.getT2());
                            return options;
                        });
    }

    @PostMapping("/houses/_query")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<PropertyHousePage> houses(
            @PathVariable String projectId, @RequestBody HouseQuery query) {
        return settings.houses(
                projectId, null, query.keyword(), query.pageIndex(), query.pageSize());
    }

    @GetMapping("/houses/{id}")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<PropertyHouseContext> house(
            @PathVariable String projectId, @PathVariable String id) {
        return settings.houseContext(projectId, id);
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }

    public record HouseQuery(String keyword, int pageIndex, int pageSize) {}
}
