package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.MonthClosingAutomaticRunEntity;
import org.jetlinks.pro.property.finance.service.MonthClosingScheduleService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 只开放有授权的配置和摘要查询；不提供后台身份或匿名执行入口。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/month-closing")
@Resource(id = "property-finance-month-closing", name = "财务月结")
public class MonthClosingScheduleController {
    private final MonthClosingScheduleService service;

    @GetMapping("/schedule")
    @QueryAction
    public Mono<MonthClosingScheduleService.View> get(@PathVariable String projectId) {
        return service.get(projectId);
    }

    @PutMapping("/schedule")
    @ResourceAction(id = "save", name = "月结规则")
    public Mono<MonthClosingScheduleService.View> save(
            @PathVariable String projectId, @RequestBody MonthClosingScheduleService.Input input) {
        return service.save(projectId, input);
    }

    @PostMapping("/automatic-runs/_query")
    @QueryAction
    public Mono<PagerResult<MonthClosingAutomaticRunEntity>> history(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.history(projectId, query);
    }
}
