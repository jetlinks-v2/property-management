package org.jetlinks.pro.property.facility.web;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.EnergyTargetEntity;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 同一权限与统计口径服务于工作台、报表和导出。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/reports")
@Resource(id = "facility-report", name = "设施能源统计")
public class FacilityReportController {

  private final FacilityReports reports;
  private final FacilityReportAnalysis analysis;
  private final EnergyTargetStore targets;
  private final FacilityQueries queries;

  @GetMapping("/overview")
  @QueryAction
  public Mono<Map<String, Object>> overview(
    @RequestParam(required = false) String projectId
  ) {
    return reports.overview(projectId);
  }

  @GetMapping("/energy")
  @QueryAction
  public Mono<List<Map<String, Object>>> energy(
    @RequestParam(required = false) String projectId,
    @RequestParam long from,
    @RequestParam long to,
    @RequestParam(defaultValue = "master") String level
  ) {
    return reports.energy(projectId, from, to, level);
  }

  @GetMapping("/periods")
  @QueryAction
  public Mono<List<Map<String, Object>>> periods(
    @RequestParam(required = false) String projectId,
    @RequestParam String period,
    @RequestParam int year,
    @RequestParam(defaultValue = "1") int month,
    @RequestParam(defaultValue = "master") String level
  ) {
    return reports.periods(projectId, period, year, month, level);
  }

  @GetMapping("/operations")
  @QueryAction
  public Mono<Map<String, Object>> operations(
    @RequestParam(required = false) String projectId,
    @RequestParam long from,
    @RequestParam long to
  ) {
    return reports.operations(projectId, from, to);
  }

  @GetMapping("/work")
  @QueryAction
  public Mono<Map<String, Object>> work(@RequestParam(required = false) String projectId,
    @RequestParam long from, @RequestParam long to, @RequestParam(defaultValue = "inspection") String kind) {
    return analysis.work(projectId, from, to, kind);
  }

  @GetMapping("/faults")
  @QueryAction
  public Mono<Map<String, Object>> faults(@RequestParam(required = false) String projectId,
    @RequestParam long from, @RequestParam long to) {
    return analysis.faults(projectId, from, to);
  }

  @GetMapping("/alerts")
  @QueryAction
  public Mono<Map<String, Object>> alerts(@RequestParam(required = false) String projectId,
    @RequestParam long from, @RequestParam long to) {
    return analysis.alerts(projectId, from, to);
  }

  @PostMapping("/work/_query")
  @QueryAction
  public Mono<org.hswebframework.web.api.crud.entity.PagerResult<org.jetlinks.pro.property.facility.entity.TaskEntity>> workDetails(
    @RequestParam(required = false) String projectId, @RequestParam long from, @RequestParam long to,
    @RequestParam(defaultValue = "inspection") String kind, @RequestParam(required = false) String groupId,
    @RequestParam(required = false) String bucket, @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
    return analysis.workDetails(projectId, from, to, kind, groupId, bucket, query);
  }

  @PostMapping("/faults/_query")
  @QueryAction
  public Mono<org.hswebframework.web.api.crud.entity.PagerResult<org.jetlinks.pro.property.facility.entity.FaultEntity>> faultDetails(
    @RequestParam(required = false) String projectId, @RequestParam long from, @RequestParam long to,
    @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
    return analysis.faultDetails(projectId, from, to, query);
  }

  @PostMapping("/alerts/_query")
  @QueryAction
  public Mono<org.hswebframework.web.api.crud.entity.PagerResult<org.jetlinks.pro.property.facility.entity.AlertEpisodeEntity>> alertDetails(
    @RequestParam(required = false) String projectId, @RequestParam long from, @RequestParam long to,
    @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
    return analysis.alertDetails(projectId, from, to, query);
  }

  @GetMapping("/energy/analysis")
  @QueryAction
  public Mono<Map<String, Object>> energyAnalysis(@RequestParam(required = false) String projectId,
    @RequestParam long from, @RequestParam long to, @RequestParam(defaultValue = "master") String level,
    @RequestParam(defaultValue = "electricity") String energyType, @RequestParam(defaultValue = "day") String period) {
    return reports.energyAnalysis(projectId, from, to, level, energyType, period);
  }

  @PostMapping("/energy/_query")
  @QueryAction
  public Mono<org.hswebframework.web.api.crud.entity.PagerResult<Map<String, Object>>> energyDetails(
    @RequestParam(required = false) String projectId, @RequestParam long from, @RequestParam long to,
    @RequestParam(defaultValue = "master") String level, @RequestParam String energyType,
    @RequestParam(required = false) String deviceId, @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
    return reports.energyDetails(projectId, from, to, level, energyType, deviceId, query);
  }

  @GetMapping("/targets/analysis")
  @QueryAction
  public Mono<Map<String, Object>> targetAnalysis(@RequestParam(required = false) String projectId,
    @RequestParam int year, @RequestParam int month, @RequestParam String energyType) {
    return reports.targetAnalysis(projectId, year, month, energyType);
  }

  @GetMapping("/comparison")
  @QueryAction
  public Mono<List<Map<String, Object>>> comparison(
    @RequestParam(required = false) String projectId,
    @RequestParam long from,
    @RequestParam long to,
    @RequestParam(defaultValue = "master") String level
  ) {
    return reports.comparison(projectId, from, to, level);
  }

  @GetMapping("/targets/execution")
  @QueryAction
  public Mono<List<Map<String, Object>>> targetExecution(
    @RequestParam(required = false) String projectId,
    @RequestParam int year,
    @RequestParam int month
  ) {
    return reports.targetExecution(projectId, year, month);
  }

  @PostMapping("/targets/_query")
  @QueryAction
  public Mono<
    org.hswebframework.web.api.crud.entity.PagerResult<EnergyTargetEntity>
  > targets(
    @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query
  ) {
    return queries.page(
      targets,
      query,
      org.jetlinks.pro.property.facility.asset.FacilityAssetType.facilityTarget,
      "id"
    );
  }

  @PostMapping("/targets")
  @SaveAction
  public Mono<EnergyTargetEntity> target(
    @RequestBody EnergyTargetEntity input
  ) {
    return reports.target(input);
  }
}
