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

/** 告警合并与通知发件箱同一事务提交，避免保存了提醒时间但丢失通知。 */
@Service
@RequiredArgsConstructor
public class FacilityAlertWriter {

  private final AlertStore alerts;
  private final FacilityRecords records;
  private final FacilityNotifications notifications;
  private final FacilityAlertEpisodes episodes;

  @Transactional
  public Mono<Void> record(
    RuleEntity rule,
    DeviceEntity device,
    BigDecimal value,
    String unit,
    long now,
    String periodKey
  ) {
    return recordScoped(
      rule,
      device,
      device.getId(),
      null,
      value,
      unit,
      now,
      periodKey
    );
  }

  @Transactional
  public Mono<Void> recordTarget(
    RuleEntity rule,
    EnergyTargetEntity target,
    BigDecimal value,
    long now,
    String periodKey
  ) {
    return recordScoped(
      rule,
      target,
      null,
      target.getId(),
      value,
      "%",
      now,
      periodKey
    );
  }

  private Mono<Void> recordScoped(
    RuleEntity rule,
    FacilityEntity parent,
    String deviceId,
    String targetId,
    BigDecimal value,
    String unit,
    long now,
    String periodKey
  ) {
    String id = FacilityRecords.stableId(rule.getId(), parent.getId());

    String level = EnergyMath.level(
      rule.getRuleType(),
      value,
      rule.getThreshold(),
      rule.getCriticalThreshold(),
      rule.getNearRatio()
    );
    return alerts
      .findById(id)
      .map(Optional::of)
      .defaultIfEmpty(Optional.empty())
      .flatMap(previous -> {
        if (level == null && previous.isEmpty()) return Mono.empty();
        boolean create = previous.isEmpty();
        var row = previous.orElseGet(() ->
          FacilityScope.initialize(
            new AlertEntity(),
            new FacilityContext(
              rule.getTenantId(),
              rule.getApplicationId(),
              rule.getCreatorId(),
              rule.getCreatorName()
            )
          )
        );
        Mono<Void> preserve = create ? Mono.empty() : episodes.snapshot(row);
        long version = row.getVersion();
        String from = row.getState();
        row.setId(id);
        FacilityScope.inherit(row, parent);
        row.setName(rule.getName());
        row.setDeviceId(deviceId);
        row.setTargetId(targetId);
        row.setRuleId(rule.getId());
        row.setRuleType(rule.getRuleType());
        row.setEnergyType(rule.getEnergyType());
        row.setValue(value);
        row.setThreshold(rule.getThreshold());
        row.setUnit(unit);
        row.setLastTime(now);
        if (level == null) {
          if ("recovered".equals(from)) return Mono.empty();
          row.setState("recovered");
          row.setRecoveredTime(now);
          return preserve.then(records
            .update(alerts, row, version))
            .then(episodes.snapshot(row))
            .then(
              records.log(
                row,
                "alert",
                "recover",
                from,
                "recovered",
                value.toPlainString(),
                List.of()
              )
            );
        }
        boolean episode =
          create ||
          "recovered".equals(from) ||
          !periodKey.equals(row.getPeriodKey());
        boolean upgrade = !episode && rank(level) > rank(row.getLevel());
        boolean send =
          episode ||
          upgrade ||
          row.getLastNotifiedTime() == null ||
          now - row.getLastNotifiedTime() >= rule.getRepeatHours() * 3600000L;
        row.setLevel(level);
        row.setPeriodKey(periodKey);
        if (episode) {
          row.setState("open");
          row.setFirstTime(now);
          row.setEpisodeId(FacilityRecords.stableId(id, Long.toString(now)));
          row.setRecoveredTime(null);
          row.setProcessingState("open");
          row.setHandlerId(null); row.setComment(null); row.setOccurrences(1L);
        } else {
          row.setOccurrences((row.getOccurrences() == null ? 1L : row.getOccurrences()) + 1L);
        }
        if (send) {
          row.setNotificationSequence(
            (row.getNotificationSequence() == null
              ? 0
              : row.getNotificationSequence()) + 1
          );
          row.setLastNotifiedTime(now);
        }
        Mono<Void> save = create
          ? alerts.insert(row).then()
          : records
              .update(
                alerts,
                row,
                version,
                episode ? new String[] { "recoveredTime", "handlerId", "comment" } : new String[0]
              )
              .then();
        return preserve.then(save)
          .then(episodes.snapshot(row))
          .then(
            send
              ? parent instanceof DeviceEntity device
                ? notifications.enqueue(row, rule, device)
                : notifications.enqueueTarget(
                    row,
                    rule,
                    (EnergyTargetEntity) parent
                  )
              : Mono.empty()
          )
          .then(
            episode || upgrade
              ? records.log(
                  row,
                  "alert",
                  episode ? "trigger" : "escalate",
                  from,
                  row.getState(),
                  value.toPlainString(),
                  List.of()
                )
              : Mono.empty()
          );
      });
  }

  private static int rank(String level) {
    return "critical".equals(level)
      ? 3
      : "warning".equals(level)
        ? 2
        : "near".equals(level)
          ? 1
          : 0;
  }
}
