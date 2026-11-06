package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.quality.service.*;
import org.jetlinks.pro.property.quality.entity.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.*;
import java.util.*;
/** 统计、问题明细与导出均从已授权工单范围派生。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality") @Resource(id="quality-work-order",name="品质工单")
public class QualityReportController {
 private final QualityStatistics statistics;private final QualityHome home;private final QualityReports reports;private final QualityExportManager exports;
 @PostMapping("/home/overview") @QueryAction public Mono<Map<String,Object>> overview(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityHomeQuery q){return home.overview(q);}
 @PostMapping("/home/trend") @QueryAction public Mono<Map<String,Object>> trend(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityHomeQuery q){return home.trend(q);}
 @PostMapping("/home/project/compare") @QueryAction public Flux<Map<String,Object>> compare(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityHomeQuery q){return home.compare(q);}
 @PostMapping("/reports/task-statistics") @QueryAction public Flux<Map<String,Object>> taskStatistics(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityReportFilter q){return statistics.tasks(q);}
 @PostMapping("/reports/line-statistics") @QueryAction public Flux<Map<String,Object>> lineStatistics(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityReportFilter q){return statistics.lines(q);}
 @PostMapping("/statistics") @QueryAction public Mono<Map<String,Object>> summary(@RequestBody QueryParamEntity q){return reports.summary(q);}
 @PostMapping("/reports/{group}") @QueryAction public Flux<Map<String,Object>> grouped(@PathVariable String group,@RequestBody QueryParamEntity q){return reports.grouped(q,group);}
 @PostMapping("/problems/_query") @QueryAction public Mono<PagerResult<QualityRecordEntity>> problems(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityProblemQuery q){return reports.problems(q.getOrderQuery(),q.getRecordQuery());}
 @PostMapping("/exports/problems") @ResourceAction(id="export",name="导出") public Mono<QualityExportEntity> exportProblems(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityProblemQuery q){return exports.createProblems(q);}
 @PostMapping("/exports/reports") @ResourceAction(id="export",name="导出") public Mono<QualityExportEntity> exportReport(@jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.quality.api.QualityReportFilter q,@RequestParam String type){return exports.createReport(q,type);}
 @PostMapping("/exports") @ResourceAction(id="export",name="导出") public Mono<QualityExportEntity> export(@RequestBody QueryParamEntity q,@RequestParam(defaultValue="orders")String type){return exports.create(q,type);}
 @PostMapping("/exports/_query") @ResourceAction(id="export",name="导出") public Mono<PagerResult<QualityExportEntity>> exports(@RequestBody QueryParamEntity q){return exports.query(q);}
 @GetMapping("/exports/{id}/file") @ResourceAction(id="export",name="导出") public Mono<Void> download(@PathVariable String id,ServerHttpResponse response){response.getHeaders().set("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.getHeaders().set("Content-Disposition","attachment; filename=quality.xlsx");return response.writeWith(exports.download(id));}
}
