package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import reactor.core.scheduler.Schedulers;

/** 三个原巡更统计页面的事实来源；资产权限由统计服务在查询前注入。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/statistics")
@Resource(id = "patrol-report", name = "巡更统计")
public class PatrolStatisticsController {
    private final PatrolStatisticsService service;
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
        return service.report(range, people)
                .flatMap(report -> Mono.fromCallable(() -> PatrolStatisticsExport.write(report, people, qualification))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(range.from() + "-" + dimension + ".xlsx", StandardCharsets.UTF_8).build().toString())
                        .body(bytes));
    }
}
