package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.AccountingPeriodEntity;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

/** 会计目录显式创建/整年编辑，不提供物理删除或通用单行更新。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/accounting-periods")
@Resource(id = "property-finance-accounting-periods", name = "会计账期")
public class AccountingPeriodController {
    private final AccountingCalendarService service;
    private final AccountingCalendarChangeService changes;

    @GetMapping("/{year}")
    @QueryAction
    public Mono<List<AccountingPeriodEntity>> year(
            @PathVariable String projectId, @PathVariable int year) {
        return service.year(projectId, year);
    }

    @GetMapping("/month/{month}")
    @QueryAction
    public Mono<AccountingPeriodEntity> period(
            @PathVariable String projectId, @PathVariable String month) {
        return service.period(projectId, month);
    }

    @PostMapping
    @ResourceAction(id = "create", name = "创建年度")
    public Mono<List<AccountingPeriodEntity>> create(
            @PathVariable String projectId, @RequestBody AccountingCalendarRules.Input input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{year}")
    @ResourceAction(id = "save", name = "调整日期")
    public Mono<List<AccountingPeriodEntity>> edit(
            @PathVariable String projectId,
            @PathVariable int year,
            @RequestBody AccountingCalendarChangeService.Input input) {
        return changes.edit(projectId, year, input);
    }
}
