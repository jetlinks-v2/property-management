package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.overview.*;
import org.jetlinks.pro.property.finance.service.FinanceOverviewService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 功能权限与项目/空间数据权限分开校验；概览不开放通用查询或写入入口。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/overview")
@Resource(id = "property-finance-overview", name = "收费概览")
public class FinanceOverviewController {
    private final FinanceOverviewService overview;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<OverviewResult> query(@RequestBody OverviewInput input) {
        return overview.query(input);
    }
}
