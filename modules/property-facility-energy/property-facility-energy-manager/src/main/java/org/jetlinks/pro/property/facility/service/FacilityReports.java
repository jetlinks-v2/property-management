package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 有界报表，所有来源先注入资产范围；水电气分别汇总并选择一个表层级防止双计。 */
@Service
@RequiredArgsConstructor
public class FacilityReports {

  private final DeviceStore devices;
  private final TaskStore tasks;
  private final FaultStore faults;
  private final AlertStore alerts;
  private final NotificationStore notifications;
  private final MappingStore mappings;
  private final EnergyTargetStore targets;
  private final FacilityScope scope;
  private final FacilityReadings readings;
  private final FacilityRecords records;
  private final FacilityDirectory directory;

  private <T extends FacilityEntity> Mono<List<T>> rows(
    GenericReactiveCrudService<T, String> store,
    AssetType type,
    String field,
    String projectId
  ) {
    var param = QueryParamEntity.newQuery()
      .when(projectId != null && !projectId.isBlank(), q ->
        q.is("projectId", projectId)
      )
      .when("deviceId".equals(field), q -> q.notNull("deviceId"))
      .when("targetId".equals(field), q -> q.notNull("targetId"))
      .noPaging()
      .getParam();
    return scope
      .query(param, type, field)
      .flatMapMany(q -> store.createQuery().setParam(q).fetch())
      .take(20001)
      .collectList()
      .doOnNext(rows -> require(rows.size() <= 20000, "too_many"));
  }

  private <T extends FacilityEntity> Mono<List<T>> bothParents(
    GenericReactiveCrudService<T, String> store,
    String projectId
  ) {
    return Mono.zip(
      rows(store, FacilityAssetType.facilityDevice, "deviceId", projectId),
      rows(store, FacilityAssetType.facilityTarget, "targetId", projectId)
    ).map(data ->
      java.util.stream.Stream.concat(
        data.getT1().stream(),
        data.getT2().stream()
      ).toList()
    );
  }

  public Mono<Map<String, Object>> overview(String projectId) {
    return Mono.zip(
      rows(devices, FacilityAssetType.facilityDevice, "id", projectId),
      rows(tasks, FacilityAssetType.facilityTask, "id", projectId),
      rows(faults, FacilityAssetType.facilityFault, "id", projectId),
      bothParents(alerts, projectId),
      bothParents(notifications, projectId)
    ).map(t -> {
      long now = System.currentTimeMillis();
      var result = new LinkedHashMap<String, Object>();
      result.put("devices", t.getT1().size());
      result.put(
        "online",
        t
          .getT1()
          .stream()
          .filter(d -> "online".equals(d.getSourceState()))
          .count()
      );
      result.put(
        "deviceStates",
        counts(t.getT1().stream().map(DeviceEntity::getSyncState).toList())
      );
      result.put(
        "categories",
        counts(t.getT1().stream().map(DeviceEntity::getCategoryName).toList())
      );
      var work = t.getT2();
      result.put("tasks", work.size());
      long dayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
        .toInstant()
        .toEpochMilli();
      for (String kind : List.of("inspection", "maintenance"))
        result.put(
          "today" + kind,
          work
            .stream()
            .filter(
              row ->
                kind.equals(row.getKind()) &&
                row.getScheduledTime() != null &&
                row.getScheduledTime() >= dayStart &&
                row.getScheduledTime() < dayStart + 86400000 &&
                !"cancelled".equals(row.getState())
            )
            .count()
        );
      result.put(
        "taskStates",
        counts(work.stream().map(TaskEntity::getState).toList())
      );
      result.put(
        "overdue",
        work
          .stream()
          .filter(
            row ->
              row.getDeadline() < now &&
              !Set.of("completed", "cancelled").contains(row.getState())
          )
          .count()
      );
      result.put(
        "completionRate",
        work.isEmpty()
          ? null
          : BigDecimal.valueOf(
              work
                .stream()
                .filter(row -> "completed".equals(row.getState()))
                .count()
            )
              .multiply(BigDecimal.valueOf(100))
              .divide(
                BigDecimal.valueOf(work.size()),
                2,
                java.math.RoundingMode.HALF_UP
              )
      );
      result.put(
        "faultStates",
        counts(t.getT3().stream().map(FaultEntity::getState).toList())
      );
      result.put(
        "alerts",
        t
          .getT4()
          .stream()
          .filter(
            row -> !Set.of("closed", "recovered").contains(row.getState())
          )
          .count()
      );
      result.put(
        "notificationStates",
        counts(t.getT5().stream().map(NotificationEntity::getState).toList())
      );
      result.put(
        "notificationFailures",
        t
          .getT5()
          .stream()
          .filter(row -> Set.of("failed", "uncertain").contains(row.getState()))
          .count()
      );
      return result;
    });
  }

  private static Map<String, Long> counts(List<String> values) {
    var result = new LinkedHashMap<String, Long>();
    for (var value : values)
      result.merge(Objects.toString(value, "未分类"), 1L, Long::sum);
    return result;
  }

  public Mono<List<Map<String, Object>>> energy(
    String projectId,
    long from,
    long to,
    String level
  ) {
    require(
      from < to &&
        to - from <= 366L * 86400000 &&
        Set.of("master", "submeter", "branch", "other").contains(level),
      "invalid_period"
    );
    return rows(mappings, FacilityAssetType.facilityMapping, "id", projectId)
      .flatMapMany(Flux::fromIterable)
      .filter(m -> level.equals(m.getMeterLevel()))
      .concatMap(m ->
        Flux.fromIterable(m.getMetrics())
          .filter(
            metric ->
              Set.of("counter", "increment").contains(metric.getKind()) &&
              (metric.getPropertyId().equals(m.getUsagePropertyId()) ||
                (m.getUsagePropertyId() == null &&
                  m
                    .getMetrics()
                    .stream()
                    .filter(value ->
                      Set.of("counter", "increment").contains(value.getKind())
                    )
                    .count() == 1))
          )
          .concatMap(metric ->
            readings
              .usageFor(m, metric.getPropertyId(), from, to)
              .map(usage -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("deviceId", m.getDeviceId());
                row.put("projectId", m.getProjectId());
                row.put("name", m.getName());
                row.put("energyType", m.getEnergyType());
                row.put("unit", metric.getUnit());
                row.put("propertyId", metric.getPropertyId());
                row.put("usage", usage);
                return (Map<String, Object>) row;
              })
          )
      )
      .collectList();
  }

  public Mono<List<Map<String, Object>>> periods(
    String projectId,
    String period,
    int year,
    int month,
    String level
  ) {
    require(
      Set.of("day", "month", "year").contains(period) &&
        year >= 2000 &&
        year <= 2200 &&
        month >= 1 &&
        month <= 12,
      "invalid_period"
    );
    var zone = ZoneId.of("Asia/Shanghai");
    var first = LocalDate.of(year, "year".equals(period) ? 1 : month, 1);
    int count = "day".equals(period)
      ? first.lengthOfMonth()
      : "month".equals(period)
        ? 12
        : 1;
    var periods = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < count; index++) {
        var date = "day".equals(period)
          ? first.plusDays(index)
          : "month".equals(period)
            ? LocalDate.of(year, 1, 1).plusMonths(index)
            : first;
        var end = "day".equals(period)
          ? date.plusDays(1)
          : "month".equals(period)
            ? date.plusMonths(1)
            : date.plusYears(1);
        long from = date.atStartOfDay(zone).toInstant().toEpochMilli(),
          to = end.atStartOfDay(zone).toInstant().toEpochMilli();
        var row = new LinkedHashMap<String, Object>();
        row.put("period", date.toString());
        row.put("from", from);
        row.put("to", to);
        row.put("meters", new ArrayList<Map<String, Object>>());
        periods.add(row);
    }
    require(Set.of("master", "submeter", "branch", "other").contains(level), "invalid_period");
    long from = (long) periods.get(0).get("from");
    long to = (long) periods.get(periods.size() - 1).get("to");
    // 先由 AssetsHolder 筛选仪表；每个指标读取一次年度／月度序列，避免每个周期重复授权与查库。
    return rows(mappings, FacilityAssetType.facilityMapping, "id", projectId)
      .flatMapMany(Flux::fromIterable)
      .filter(mapping -> level.equals(mapping.getMeterLevel()))
      .concatMap(mapping -> Flux.fromIterable(mapping.getMetrics())
        .filter(metric -> Set.of("counter", "increment").contains(metric.getKind()) &&
          (metric.getPropertyId().equals(mapping.getUsagePropertyId()) ||
            (mapping.getUsagePropertyId() == null && mapping.getMetrics().stream()
              .filter(value -> Set.of("counter", "increment").contains(value.getKind())).count() == 1)))
        .concatMap(metric -> readings.seriesFor(mapping, metric.getPropertyId(), from, to)
          .map(source -> periods.stream().map(window -> EnergyMath.usage(source,
            (long) window.get("from"), (long) window.get("to"), mapping.getStaleAfterSeconds())).toList())
          // 保留既有单周期五万读数的上限；长区间超过上限时按原周期有界读取。
          .onErrorResume(org.hswebframework.web.exception.BusinessException.class, error ->
            "error.facility.too_many".equals(error.getMessage())
              ? Flux.fromIterable(periods).concatMap(window -> readings.usageFor(mapping,
                  metric.getPropertyId(), (long) window.get("from"), (long) window.get("to"))).collectList()
              : Mono.error(error))
          .doOnNext(usages -> {
            for (int i = 0; i < periods.size(); i++) {
              var meter = new LinkedHashMap<String, Object>();
              meter.put("deviceId", mapping.getDeviceId());
              meter.put("projectId", mapping.getProjectId());
              meter.put("name", mapping.getName());
              meter.put("energyType", mapping.getEnergyType());
              meter.put("unit", metric.getUnit());
              meter.put("propertyId", metric.getPropertyId());
              meter.put("usage", usages.get(i));
              @SuppressWarnings("unchecked")
              var meters = (List<Map<String, Object>>) periods.get(i).get("meters");
              meters.add(meter);
            }
          })))
      .then(Mono.just(periods));
  }

  public Mono<Map<String, Object>> operations(
    String projectId,
    long from,
    long to
  ) {
    require(from < to && to - from <= 366L * 86400000, "invalid_period");
    return Mono.zip(
      rows(tasks, FacilityAssetType.facilityTask, "id", projectId),
      rows(faults, FacilityAssetType.facilityFault, "id", projectId),
      bothParents(notifications, projectId)
    ).map(data -> {
      Map<String, Object> result = new LinkedHashMap<>();
      long now = System.currentTimeMillis();
      var summaries = new ArrayList<Map<String, Object>>();
      for (String kind : List.of("inspection", "maintenance")) {
        var work = data
          .getT1()
          .stream()
          .filter(
            row ->
              kind.equals(row.getKind()) &&
              row.getScheduledTime() >= from &&
              row.getScheduledTime() < to &&
              !"cancelled".equals(row.getState())
          )
          .toList();
        long completed = work
          .stream()
          .filter(row -> "completed".equals(row.getState()))
          .count();
        long late = work
          .stream()
          .filter(
            row ->
              row.getDeadline() < now &&
              (row.getCompletedTime() == null ||
                row.getCompletedTime() > row.getDeadline())
          )
          .count();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("kind", kind);
        summary.put("total", work.size());
        summary.put("completed", completed);
        summary.put("overdue", late);
        summary.put("completionRate", percentage(completed, work.size()));
        summary.put("overdueRate", percentage(late, work.size()));
        summaries.add(summary);
      }
      result.put("tasks", summaries);
      var zone = ZoneId.of("Asia/Shanghai");
      var trends = new TreeMap<String, Long>();
      data
        .getT2()
        .stream()
        .filter(row -> row.getCreateTime() >= from && row.getCreateTime() < to)
        .forEach(row ->
          trends.merge(
            Instant.ofEpochMilli(row.getCreateTime())
              .atZone(zone)
              .toLocalDate()
              .toString(),
            1L,
            Long::sum
          )
        );
      result.put("faultTrend", trends);
      result.put(
        "notifications",
        counts(
          data
            .getT3()
            .stream()
            .filter(
              row -> row.getCreateTime() >= from && row.getCreateTime() < to
            )
            .map(NotificationEntity::getState)
            .toList()
        )
      );
      return result;
    });
  }

  private static BigDecimal percentage(long part, long total) {
    return total == 0
      ? null
      : BigDecimal.valueOf(part)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
  }

  /** Compare the selected interval with its preceding interval and the same Shanghai calendar interval last year. */
  public Mono<List<Map<String, Object>>> comparison(
    String projectId,
    long from,
    long to,
    String level
  ) {
    require(from < to && to - from <= 366L * 86400000, "invalid_period");
    var zone = ZoneId.of("Asia/Shanghai");
    long duration = to - from;
    long yearFrom = Instant.ofEpochMilli(from)
      .atZone(zone)
      .minusYears(1)
      .toInstant()
      .toEpochMilli();
    long yearTo = Instant.ofEpochMilli(to)
      .atZone(zone)
      .minusYears(1)
      .toInstant()
      .toEpochMilli();
    return Mono.zip(
      energy(projectId, from, to, level),
      energy(projectId, from - duration, from, level),
      energy(projectId, yearFrom, yearTo, level)
    ).map(data ->
      data
        .getT1()
        .stream()
        .map(current -> {
          var row = new LinkedHashMap<String, Object>(current);
          for (var pair : Map.of(
            "previous",
            data.getT2(),
            "lastYear",
            data.getT3()
          ).entrySet()) {
            var previous = pair
              .getValue()
              .stream()
              .filter(
                other ->
                  Objects.equals(
                    current.get("deviceId"),
                    other.get("deviceId")
                  ) &&
                  Objects.equals(
                    current.get("propertyId"),
                    other.get("propertyId")
                  ) &&
                  Objects.equals(current.get("unit"), other.get("unit"))
              )
              .findFirst();
            if (previous.isPresent()) {
              var base = (EnergyMath.Usage) previous.get().get("usage");
              var value = (EnergyMath.Usage) current.get("usage");
              row.put(pair.getKey(), base);
              row.put(
                pair.getKey() + "Rate",
                base.value().signum() == 0 ||
                  !base.complete() ||
                  !value.complete()
                  ? null
                  : value
                      .value()
                      .subtract(base.value())
                      .multiply(BigDecimal.valueOf(100))
                      .divide(base.value(), 2, java.math.RoundingMode.HALF_UP)
              );
            }
          }
          return (Map<String, Object>) row;
        })
        .toList()
    );
  }

  /** Targets use kWh for electricity and m³ for water/gas; incompatible units remain visible as incomplete. */
  public Mono<List<Map<String, Object>>> targetExecution(
    String projectId,
    int year,
    int month
  ) {
    require(
      year >= 2000 && year <= 2200 && month >= 1 && month <= 12,
      "invalid_period"
    );
    var zone = ZoneId.of("Asia/Shanghai");
    var start = LocalDate.of(year, month, 1).atStartOfDay(zone);
    long from = start.toInstant().toEpochMilli(),
      to = Math.min(
        System.currentTimeMillis(),
        start.plusMonths(1).toInstant().toEpochMilli()
      );
    if (to <= from) return Mono.just(List.of());
    return Mono.zip(
      rows(targets, FacilityAssetType.facilityTarget, "id", projectId),
      energy(projectId, from, to, "master")
    ).map(data -> {
      var results = new ArrayList<Map<String, Object>>();
      for (var target : data.getT1()) {
        if (target.getYear() != year || target.getMonth() != month) continue;
        String unit = "electricity".equals(target.getEnergyType())
          ? "kWh"
          : "m³";
        var meters = data
          .getT2()
          .stream()
          .filter(
            row ->
              target.getEnergyType().equals(row.get("energyType")) &&
              target.getProjectId().equals(row.get("projectId"))
          )
          .toList();
        BigDecimal actual = BigDecimal.ZERO;
        boolean complete = !meters.isEmpty(),
          unitsValid = true;
        for (var meter : meters) {
          var usage = (EnergyMath.Usage) meter.get("usage");
          if (!unit.equals(meter.get("unit"))) {
            complete = false;
            unitsValid = false;
            continue;
          }
          actual = actual.add(usage.value());
          complete &= usage.complete();
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("id", target.getId());
        row.put("projectId", target.getProjectId());
        row.put("energyType", target.getEnergyType());
        row.put("unit", unit);
        row.put("target", target.getTargetUsage());
        row.put("actual", actual);
        row.put("complete", complete);
        row.put(
          "executionRate",
          target.getTargetUsage().signum() == 0 || !unitsValid
            ? null
            : actual
                .multiply(BigDecimal.valueOf(100))
                .divide(
                  target.getTargetUsage(),
                  2,
                  java.math.RoundingMode.HALF_UP
                )
        );
        row.put("exceeded", actual.compareTo(target.getTargetUsage()) >= 0);
        results.add(row);
      }
      return results;
    });
  }

  @Transactional
  public Mono<EnergyTargetEntity> target(EnergyTargetEntity input) {
    require(
      Set.of("water", "electricity", "gas").contains(input.getEnergyType()) &&
        input.getYear() != null &&
        input.getYear() >= 2000 &&
        input.getYear() <= 2200 &&
        input.getMonth() != null &&
        input.getMonth() >= 1 &&
        input.getMonth() <= 12 &&
        input.getTargetUsage() != null &&
        input.getTargetUsage().signum() >= 0,
      "invalid_rule"
    );
    return directory
      .project(input.getProjectId())
      .then(
        records.assertProject(input.getProjectId(), CrudAssetPermission.save)
      )
      .then(scope.current())
      .flatMap(c -> {
        String id = FacilityRecords.stableId(
          c.tenantId(),
          c.applicationId(),
          input.getProjectId(),
          input.getEnergyType(),
          input.getYear().toString(),
          input.getMonth().toString()
        );
        return targets
          .findById(id)
          .map(Optional::of)
          .defaultIfEmpty(Optional.empty())
          .flatMap(old -> {
            var row = old.orElseGet(() ->
              FacilityScope.initialize(new EnergyTargetEntity(), c)
            );
            long version = row.getVersion();
            require(old.isEmpty() || version == input.getVersion(), "conflict");
            row.setId(id);
            row.setProjectId(input.getProjectId());
            row.setOrganizationId(input.getProjectId());
            row.setEnergyType(input.getEnergyType());
            row.setYear(input.getYear());
            row.setMonth(input.getMonth());
            row.setTargetUsage(input.getTargetUsage());
            row.setManagementArea(input.getManagementArea());
            return old.isEmpty()
              ? records.insert(targets, row, FacilityAssetType.facilityTarget)
              : records.update(
                  targets,
                  row,
                  version,
                  FacilityRecords.nullFields(row, "managementArea")
                );
          });
      });
  }
}
