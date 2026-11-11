package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverview;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverviewService;
import org.hswebframework.web.api.crud.entity.PagerResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import reactor.core.scheduler.Schedulers;

/** 巡更统计工作台及历史报表接口。所有聚合、下钻、导出由服务读取同一授权范围。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/statistics")
@Resource(id = "patrol-report", name = "巡更统计")
public class PatrolStatisticsController {
    private final PatrolStatisticsService service;
    private final PatrolOverviewService overview;

    @PostMapping("/overview")
    @QueryAction
    public Mono<PatrolOverview.Report> overview(@RequestBody @Valid PatrolOverview.Range range) {
        return overview.aggregate(range).map(result -> result.report());
    }

    @PostMapping("/overview/tasks")
    @QueryAction
    public Mono<PagerResult<PatrolOverview.PointDay>> tasks(@RequestBody @Valid PatrolOverview.Drill request) {
        return overview.drill(request);
    }

    @PostMapping("/overview/detail")
    @QueryAction
    public Mono<PatrolOverview.Detail> detail(@RequestBody @Valid PatrolOverview.DetailRequest request) {
        return overview.detail(request);
    }

    /** 导出与工作台相同的归属日、分类、等级和项目筛选，序列化移出事件循环。 */
    @PostMapping("/overview/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> exportOverview(@RequestBody @Valid PatrolOverview.Range range) {
        return overview.aggregate(range)
                .flatMap(result -> Mono.fromCallable(() -> PatrolStatisticsExport.writeOverview(result))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patrol-statistics.xlsx")
                        .body(bytes));
    }
    @PostMapping("/points")
    @QueryAction
    public Mono<List<PatrolStatisticsService.Row>> points(@RequestBody PatrolStatisticsService.Range range) {
        return service.query(range, false);
    }
    @PostMapping("/people")
    @QueryAction
    public Mono<List<PatrolStatisticsService.Row>> people(@RequestBody PatrolStatisticsService.Range range) {
        return service.query(range, true);
    }

    /** 表格与总计来自同一次权限过滤后的统计，避免前端仅汇总当前页。 */
    @PostMapping("/{dimension:points|people}/report")
    @QueryAction
    public Mono<PatrolStatisticsService.Report> report(@PathVariable String dimension,
                                                       @RequestBody PatrolStatisticsService.Range range) {
        return service.report(range, "people".equals(dimension));
    }

    /** 导出复用报表查询，序列化移出事件循环，不能绕过原统计资产权限。 */
    @PostMapping("/{dimension:points|people}/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> export(@PathVariable String dimension,
                                               @RequestParam(defaultValue = "false") boolean qualification,
                                               @RequestBody PatrolStatisticsService.Range range) {
        boolean people = "people".equals(dimension);
        return service.reportByProject(range, people)
                .flatMap(report -> Mono.fromCallable(() -> PatrolStatisticsExport.write(report, people, qualification))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(range.from() + "-" + dimension + ".xlsx", StandardCharsets.UTF_8).build().toString())
                        .body(bytes));
    }
}
