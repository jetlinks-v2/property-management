package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.Map;

/** 首页统计保留项目、资料、问题分类和按年月份趋势。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/statistics")
@Resource(id = "handover-problem", name = "承接查验问题")
public class HandoverStatisticsController {
    private final HandoverStatisticsService service;

    @GetMapping
    @QueryAction
    public Mono<Map<String, Object>> statistics(
            HandoverQuery input, @RequestParam(required = false) Integer year) {
        return service.statistics(input, year);
    }
}
