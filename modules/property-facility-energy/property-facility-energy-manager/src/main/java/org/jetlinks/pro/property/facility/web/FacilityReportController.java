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
