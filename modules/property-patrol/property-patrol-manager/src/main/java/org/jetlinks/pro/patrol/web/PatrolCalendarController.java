package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.service.PatrolCalendarService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.nio.charset.StandardCharsets;

/** 排班日历与导出共用同一资产过滤后的数据，不提供独立写入入口。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/calendar")
@Resource(id = "patrol-calendar", name = "排班日历")
public class PatrolCalendarController {
    private final PatrolCalendarService service;
    @GetMapping("/month")
    @QueryAction
    public Mono<PatrolCalendarService.Month> month(@RequestParam String month) {
        return service.month(YearMonth.parse(month));
    }
    @GetMapping("/day")
    @QueryAction
    public Mono<PatrolCalendarService.Detail> day(@RequestParam String date) {
        return service.day(LocalDate.parse(date));
    }
    @GetMapping("/day/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> export(@RequestParam String date) {
        return day(date).flatMap(detail -> Mono.fromCallable(() -> PatrolCalendarExport.write(detail))
                // XLSX 序列化不占用事件循环，数据查询及权限仍保留响应式上下文。
                .subscribeOn(Schedulers.boundedElastic()))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(date + "-patrol.xlsx", StandardCharsets.UTF_8).build().toString())
                        .body(bytes));
    }
}
