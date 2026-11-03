package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

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
}
