package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 真实余额和用量分别判断；持续异常合并，恢复/升级留痕并生成幂等通知。 */
@Service
@RequiredArgsConstructor
public class FacilityAlerts {

  private final FacilityAlertWriter writer;
  private final FacilityReports reports;
  private final EnergyTargetStore targets;
  private final RuleStore rules;
  private final AlertStore alerts;
  private final DeviceStore devices;
  private final GroupStore groups;
  private final TaskStore tasks;
  private final FacilityDevices deviceManager;
  private final FacilityMappings mappings;
  private final FacilityReadings readings;
  private final FacilityNotifications notifications;
  private final FacilityScope scope;
  private final FacilityDirectory directory;
  private final FacilityRecords records;

  @Transactional
  public Mono<RuleEntity> save(RuleEntity input) {
    require(
      input.getName() != null &&
        !input.getName().isBlank() &&
        Set.of(
          "usage",
          "balance",
          "instant",
          "offline",
          "stale",
          "overdue",
          "dueSoon",
          "reading-anomaly",
          "state",
          "plan"
        ).contains(input.getRuleType()),
      "invalid_rule"
    );
    require(
      input.getThreshold() != null &&
        input.getThreshold().signum() >= 0 &&
        input.getRepeatHours() != null &&
        input.getRepeatHours() > 0 &&
        input.getRepeatHours() <= 720,
      "invalid_rule"
    );
    if (
      Set.of(
        "usage",
        "balance",
        "instant",
        "stale",
        "reading-anomaly",
        "state"
      ).contains(input.getRuleType())
    ) require(
      input.getPropertyId() != null &&
        Set.of("water", "electricity", "gas").contains(input.getEnergyType()),
      "invalid_rule"
    );
    if ("usage".equals(input.getRuleType())) require(
      Set.of("day", "month").contains(input.getPeriod()),
      "invalid_rule"
    );
    if ("plan".equals(input.getRuleType())) require(
      Set.of("water", "electricity", "gas").contains(input.getEnergyType()) &&
        input.getNotifyUserId() != null &&
        !input.getNotifyUserId().isBlank() &&
        (input.getDeviceId() == null || input.getDeviceId().isBlank()) &&
        (input.getGroupId() == null || input.getGroupId().isBlank()) &&
        input.getThreshold().signum() > 0,
      "invalid_rule"
    );
    if ("state".equals(input.getRuleType())) require(
      input.getStateValue() != null && !input.getStateValue().isBlank(),
      "invalid_rule"
    );
    if ("dueSoon".equals(input.getRuleType())) require(
      input.getStaleAfterSeconds() != null && input.getStaleAfterSeconds() > 0,
      "invalid_rule"
    );
    if (input.getNearRatio() != null) require(
      input.getNearRatio().signum() > 0 &&
        input.getNearRatio().compareTo(BigDecimal.ONE) < 0,
      "invalid_rule"
    );
    if (input.getCriticalThreshold() != null) require(
      "balance".equals(input.getRuleType())
        ? input.getCriticalThreshold().compareTo(input.getThreshold()) <= 0
        : input.getCriticalThreshold().compareTo(input.getThreshold()) >= 0,
      "invalid_rule"
    );
    if ("balance".equals(input.getRuleType())) require(
      input.getStaleAfterSeconds() != null && input.getStaleAfterSeconds() > 0,
      "invalid_rule"
    );
    if (Boolean.TRUE.equals(input.getSmsEnabled())) require(
      input.getSmsNotifierId() != null && input.getSmsTemplateId() != null,
      "sms_configuration_required"
    );
    Mono<Void> refs = directory
      .project(input.getProjectId())
      .then(
        records.assertProject(input.getProjectId(), CrudAssetPermission.save)
      );
    if (input.getDeviceId() != null && !input.getDeviceId().isBlank()) refs =
      refs.then(
        deviceManager
          .read(input.getDeviceId())
          .doOnNext(d ->
            require(
              d.getProjectId().equals(input.getProjectId()),
              "project_mismatch"
            )
          )
          .then()
      );
    if (input.getGroupId() != null && !input.getGroupId().isBlank()) refs =
      refs.then(
        records
          .read(
            groups,
            input.getGroupId(),
            FacilityAssetType.facilityGroup,
            CrudAssetPermission.read
          )
          .doOnNext(g ->
            require(
              g.getProjectId().equals(input.getProjectId()),
              "project_mismatch"
            )
          )
          .then()
      );
    if ("plan".equals(input.getRuleType())) refs = refs
      .then(directory.employee(input.getNotifyUserId()))
      .then();
    return refs.then(scope.current()).flatMap(c -> {
      boolean create = input.getId() == null;
      return (
        create
          ? Mono.just(FacilityScope.initialize(new RuleEntity(), c))
          : records.read(
              rules,
              input.getId(),
              FacilityAssetType.facilityRule,
              CrudAssetPermission.save
            )
      ).flatMap(row -> {
        require(
          create ||
            (Objects.equals(row.getVersion(), input.getVersion()) &&
              row.getProjectId().equals(input.getProjectId())),
          "conflict"
        );
        long v = row.getVersion();
        row.setProjectId(input.getProjectId());
        row.setOrganizationId(input.getProjectId());
        row.setName(input.getName());
        row.setRuleType(input.getRuleType());
        row.setEnergyType(input.getEnergyType());
        row.setDeviceId(input.getDeviceId());
        row.setGroupId(input.getGroupId());
        row.setPropertyId(input.getPropertyId());
        row.setStateValue(input.getStateValue());
        row.setNotifyUserId(input.getNotifyUserId());
        row.setPeriod(input.getPeriod());
        row.setThreshold(input.getThreshold());
        row.setCriticalThreshold(input.getCriticalThreshold());
        row.setNearRatio(input.getNearRatio());
        row.setStaleAfterSeconds(input.getStaleAfterSeconds());
        row.setRepeatHours(input.getRepeatHours());
        row.setSmsEnabled(input.getSmsEnabled());
        row.setSmsNotifierId(input.getSmsNotifierId());
        row.setSmsTemplateId(input.getSmsTemplateId());
        row.setEnabled(input.getEnabled());
        return create
          ? records.insert(rules, row, FacilityAssetType.facilityRule)
          : records.update(
              rules,
              row,
              v,
              FacilityRecords.nullFields(
                row,
                "energyType",
                "deviceId",
                "groupId",
                "propertyId",
                "stateValue",
                "notifyUserId",
                "period",
                "criticalThreshold",
                "nearRatio",
                "staleAfterSeconds",
                "smsNotifierId",
                "smsTemplateId"
              )
            );
      });
    });
  }

  public Mono<Void> evaluate(RuleEntity rule) {
    if ("plan".equals(rule.getRuleType())) return records
      .read(
        rules,
        rule.getId(),
        FacilityAssetType.facilityRule,
        CrudAssetPermission.read
      )
      .then(
        Mono.defer(() -> {
          long now = System.currentTimeMillis();
          var date = Instant.ofEpochMilli(now).atZone(
            ZoneId.of("Asia/Shanghai")
          );
          return reports
            .targetExecution(
              rule.getProjectId(),
              date.getYear(),
              date.getMonthValue()
            )
            .flatMapMany(Flux::fromIterable)
            .filter(row ->
              Objects.equals(rule.getEnergyType(), row.get("energyType"))
            )
            // Positive observed usage can establish exceedance even while boundary data are incomplete.
            // Missing units and missing baselines never produce an invented percentage.
            .filter(
              row ->
                row.get("executionRate") != null &&
                ((BigDecimal) row.get("actual")).signum() > 0
            )
            .concatMap(row ->
              records
                .read(
                  targets,
                  (String) row.get("id"),
                  FacilityAssetType.facilityTarget,
                  CrudAssetPermission.read
                )
                .flatMap(target ->
                  writer.recordTarget(
                    rule,
                    target,
                    (BigDecimal) row.get("executionRate"),
                    now,
                    date.getYear() + "-" + date.getMonthValue()
                  )
                )
            )
            .then();
        })
      );
    var param = QueryParamEntity.newQuery()
      .where("projectId", rule.getProjectId())
      .and("syncState", "active")
      .when(rule.getDeviceId() != null && !rule.getDeviceId().isBlank(), q ->
        q.and("id", rule.getDeviceId())
      )
      .noPaging()
      .getParam();
    return records
      .read(
        rules,
        rule.getId(),
        FacilityAssetType.facilityRule,
        CrudAssetPermission.read
      )
      .then(scope.query(param, FacilityAssetType.facilityDevice, "id"))
      .flatMapMany(q -> devices.createQuery().setParam(q).fetch())
      .take(20001)
      .collectList()
      .doOnNext(rows -> require(rows.size() <= 20000, "too_many"))
      .flatMapMany(Flux::fromIterable)
      .concatMap(device ->
        matchesGroup(rule, device)
          .filter(Boolean.TRUE::equals)
          .flatMap(ignore -> evaluateDevice(rule, device))
      )
      .then();
  }

  private Mono<Boolean> matchesGroup(RuleEntity rule, DeviceEntity device) {
    if (
      rule.getGroupId() == null || rule.getGroupId().isBlank()
    ) return Mono.just(true);
    return records
      .read(
        groups,
        rule.getGroupId(),
        FacilityAssetType.facilityGroup,
        CrudAssetPermission.read
      )
      .map(g -> g.getDeviceIds().contains(device.getId()));
  }

  private Mono<Void> evaluateDevice(RuleEntity rule, DeviceEntity device) {
    long now = System.currentTimeMillis();
    if ("offline".equals(rule.getRuleType())) return writer.record(
      rule,
      device,
      "offline".equals(device.getSourceState())
        ? BigDecimal.ONE
        : BigDecimal.ZERO,
      "state",
      now,
      "current"
    );
    if (Set.of("overdue", "dueSoon").contains(rule.getRuleType())) return tasks
      .createQuery()
      .where("tenantId", device.getTenantId())
      .and("applicationId", device.getApplicationId())
      .and("deviceId", device.getId())
      .and("projectId", device.getProjectId())
      .and("deleted", false)
      .in(
        "state",
        List.of(
          "open",
          "claimed",
          "executing",
          "rectify",
          "external-submitted",
          "review"
        )
      )
      .when("overdue".equals(rule.getRuleType()), q -> q.lt("deadline", now))
      .when("dueSoon".equals(rule.getRuleType()), q ->
        q
          .gte("deadline", now)
          .lte("deadline", now + rule.getStaleAfterSeconds() * 1000)
      )
      .count()
      .flatMap(n ->
        writer.record(
          rule,
          device,
          BigDecimal.valueOf(n),
          "tasks",
          now,
          "current"
        )
      );
    return mappings
      .forDevice(device.getId())
      .filter(
        m ->
          Boolean.TRUE.equals(m.getEnabled()) &&
          Boolean.TRUE.equals(device.getEnergyEnabled()) &&
          Objects.equals(m.getEnergyType(), rule.getEnergyType())
      )
      .flatMap(mapping -> {
        var metric = mapping
          .getMetrics()
          .stream()
          .filter(m -> Objects.equals(m.getPropertyId(), rule.getPropertyId()))
          .findFirst()
          .orElse(null);
        if (metric == null) return Mono.empty();
        var day = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai"));
        long from = (
          "month".equals(rule.getPeriod())
            ? day.withDayOfMonth(1).toLocalDate()
            : day.toLocalDate()
        )
          .atStartOfDay(day.getZone())
          .toInstant()
          .toEpochMilli();
        if ("reading-anomaly".equals(rule.getRuleType())) return readings
          .anomalies(device.getId(), metric.getPropertyId(), from, now + 1)
          .flatMap(count ->
            writer.record(
              rule,
              device,
              BigDecimal.valueOf(count),
              "samples",
              now,
              Long.toString(from)
            )
          );
        if ("usage".equals(rule.getRuleType())) {
          require(
            Set.of("counter", "increment").contains(metric.getKind()),
            "invalid_mapping"
          );
          return readings
            .usage(device.getId(), metric.getPropertyId(), from, now + 1)
            .flatMap(usage ->
              usage.accepted() == 0
                ? Mono.empty()
                : writer.record(
                    rule,
                    device,
                    usage.value(),
                    metric.getUnit(),
                    now,
                    Long.toString(from)
                  )
            );
        }
        return readings
          .series(
            device.getId(),
            metric.getPropertyId(),
            now - 7L * 86400000,
            now + 1
          )
          .flatMap(rows -> {
            var last = rows
              .stream()
              .filter(reading -> reading.getObservedTime() <= now)
              .max(Comparator.comparing(ReadingEntity::getObservedTime));
            if ("stale".equals(rule.getRuleType())) return writer.record(
              rule,
              device,
              BigDecimal.valueOf(
                last.isPresent()
                  ? (now - last.get().getObservedTime()) / 1000
                  : (now - mapping.getCreateTime()) / 1000
              ),
              "seconds",
              now,
              "current"
            );
            long stale =
              rule.getStaleAfterSeconds() == null
                ? mapping.getStaleAfterSeconds()
                : rule.getStaleAfterSeconds();
            if ("state".equals(rule.getRuleType())) {
              require("state".equals(metric.getKind()), "invalid_mapping");
              if (
                last.isEmpty() ||
                now - last.get().getObservedTime() > stale * 1000
              ) return Mono.empty();
              return writer.record(
                rule,
                device,
                Objects.equals(rule.getStateValue(), last.get().getTextValue())
                  ? BigDecimal.ONE
                  : BigDecimal.ZERO,
                "state",
                now,
                "current"
              );
            }
            if (
              last.isEmpty() ||
              last.get().getValue() == null ||
              now - last.get().getObservedTime() > stale * 1000
            ) return Mono.empty();
            require(
              !"balance".equals(rule.getRuleType()) ||
                "balance".equals(metric.getKind()),
              "invalid_mapping"
            );
            require(
              !"instant".equals(rule.getRuleType()) ||
                "instant".equals(metric.getKind()),
              "invalid_mapping"
            );
            return writer.record(
              rule,
              device,
              last.get().getValue(),
              metric.getUnit(),
              now,
              "current"
            );
          });
      });
  }

  public Mono<AlertEntity> action(
    String id,
    String state,
    String comment,
    long version
  ) {
    return alerts
      .findById(id)
      .switchIfEmpty(
        Mono.error(new org.hswebframework.web.exception.NotFoundException())
      )
      .flatMap(row ->
        (row.getTargetId() == null
          ? deviceManager
              .writable(row.getDeviceId())
              .flatMap(parent -> records.child(row, parent))
          : records
              .read(
                targets,
                row.getTargetId(),
                FacilityAssetType.facilityTarget,
                CrudAssetPermission.save
              )
              .flatMap(parent -> records.child(row, parent))
        )
          .then(scope.current())
          .flatMap(c -> {
            require(
              Set.of("acknowledged", "processing", "closed").contains(state) &&
                row.getVersion() == version,
              "invalid_state"
            );
            String from = row.getState();
            row.setState(state);
            row.setHandlerId(c.userId());
            row.setComment(comment);
            return records
              .update(alerts, row, version)
              .flatMap(saved ->
                records
                  .log(row, "alert", "handle", from, state, comment, List.of())
                  .thenReturn(saved)
              );
          })
      );
  }
}
