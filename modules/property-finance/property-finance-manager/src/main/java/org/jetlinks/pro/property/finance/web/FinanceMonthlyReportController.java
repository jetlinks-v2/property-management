package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.FinanceExportTaskEntity;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 查询和导出独立授权；导出任务及下载保持项目和空间权限。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/reports/monthly")
@org.hswebframework.web.authorization.annotation.Resource(
        id = "property-finance-report-monthly",
        name = "财务月报")
public class FinanceMonthlyReportController {
    private final FinanceAnalysisService analysis;
    private final FinanceExportJobs jobs;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<AnalysisResult> query(@RequestBody AnalysisInput input) {
        return analysis.query("monthly", input);
    }

    @PostMapping("/_exports/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<FinanceExportTaskEntity> create(
            @RequestBody AnalysisInput input, @PathVariable String format) {
        return jobs.create("monthly", input, format);
    }

    @GetMapping("/_exports/{id}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<FinanceExportTaskEntity> status(@PathVariable String id) {
        return jobs.status("monthly", id);
    }

    @GetMapping("/_exports/{id}/_download")
    @ResourceAction(id = "export", name = "导出")
    public Mono<org.springframework.http.ResponseEntity<Resource>> download(
            @PathVariable String id) {
        return jobs.downloadResponse("monthly", id);
    }
}
