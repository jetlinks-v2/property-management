package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 平台记录与员工消息在一个事务落库；来源重放不能重置人工状态或重复提醒。 */
@Service
@RequiredArgsConstructor
public class FacilitySaasAlarmWriter {
  private final AlertStore alerts;
  private final FacilityDevices devices;
  private final FacilityRecords records;
  private final FacilityAlertEpisodes episodes;
  private final FacilityEmployeeMessages messages;
  private final FacilityScope scope;
  private final FacilityDirectory directory;

  @Transactional
  public Mono<Void> accept(DeviceEntity expected, CloudAlarmRecord source) {
    FacilityScope.require(source.getId() != null && !source.getId().isBlank() && source.belongsTo(expected.getCloudDeviceId())
      && source.getAlarmTime() != null && source.getAlarmTime() > 0
      && Set.of("normal", "warning").contains(source.stateValue()), "invalid_alarm_source");
    // 重查当前资产与应用绑定，查询期间发生解绑或项目变更时不能继续导入旧归属。
    return devices.read(expected.getId()).flatMap(devices::requireBound)
      .doOnNext(current -> FacilityScope.require(Objects.equals(current.getProjectId(), expected.getProjectId()), "source_changed"))
      .flatMap(device -> (device.getSpaceId() == null || device.getSpaceId().isBlank() ? Mono.just(device) :
        directory.space(device.getProjectId(), device.getSpaceId()).map(space -> {
          device.setSpaceName(Objects.toString(space.getFullName(), space.getName())); return device;
        })))
      .flatMap(device -> scope.current().flatMap(context -> {
        String id = FacilityRecords.stableId(context.tenantId(), context.applicationId(), device.getId(), "saas", source.getId());
        return alerts.findById(id).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(previous -> {
          var row = previous.orElseGet(() -> FacilityScope.initialize(new AlertEntity(), context));
          if (previous.isPresent() && (row.getFirstTime() > source.getAlarmTime()
            || Objects.equals(row.getFirstTime(), source.getAlarmTime()) && row.getLastTime() > lastTime(source))) return Mono.empty();
          boolean create = previous.isEmpty(), round = create || !Objects.equals(row.getFirstTime(), source.getAlarmTime());
          boolean active = "warning".equals(source.stateValue());
          boolean levelChanged = !round && !Objects.equals(row.getSourceLevel(), source.getLevel());
          boolean send = active && (round || levelChanged);
          if (!round && !levelChanged && Objects.equals(row.getSourceState(), source.stateValue())
            && Objects.equals(row.getLastTime(), lastTime(source)) && Objects.equals(row.getSourceReason(), reason(source))
            && Objects.equals(row.getSourceLevel(), source.getLevel())
            && Objects.equals(row.getSourceCondition(), source.getTriggerDesc())
            && Objects.equals(row.getSourceConfigId(), source.getAlarmConfigId())
            && Objects.equals(row.getRecoveredTime(), active ? null : source.getHandleTime())) return Mono.empty();
          Mono<Void> preserve = create ? Mono.empty() : episodes.snapshot(row);
          // preserve 的订阅必须先于修改实体，否则旧轮次会被新轮次覆盖。
          return preserve.then(Mono.defer(() -> {
            long version = row.getVersion();
            FacilityScope.inherit(row, device); row.setId(id); row.setSource("saas");
            row.setRuleId("saas:" + source.getId()); row.setRuleType("saas"); row.setDeviceId(device.getId());
            row.setSourceRecordId(source.getId()); row.setSourceConfigId(source.getAlarmConfigId());
            row.setName(Objects.toString(source.getAlarmName(), device.getName()));
            row.setSourceLevel(source.getLevel()); row.setLevel(source.facilityLevel());
            row.setSourceState(source.stateValue()); row.setSourceReason(reason(source)); row.setSourceCondition(source.getTriggerDesc());
            row.setDeviceName(device.getName()); row.setSpaceName(Objects.toString(device.getSpaceName(), device.getLocation()));
            row.setState(active ? "open" : "recovered");
            row.setFirstTime(source.getAlarmTime()); row.setLastTime(lastTime(source));
            // 平台 normal 可以是自动解除或人工解除，保留平台时间，不虚构设备恢复时刻。
            row.setRecoveredTime(active ? null : source.getHandleTime());
            if (round) {
              row.setProcessingState("open"); row.setHandlerId(null); row.setComment(null); row.setOccurrences(1L);
              row.setEpisodeId(FacilityRecords.stableId(id, String.valueOf(source.getAlarmTime())));
            }
            if (send) {
              row.setNotificationSequence(Objects.requireNonNullElse(row.getNotificationSequence(), 0) + 1);
              row.setLastNotifiedTime(System.currentTimeMillis());
            }
            return (create ? alerts.insert(row).thenReturn(row) : records.update(alerts, row, version,
              FacilityRecords.nullFields(row, "sourceLevel", "sourceReason", "sourceCondition", "spaceName", "recoveredTime", "handlerId", "comment")))
              .then(episodes.snapshot(row))
              .then(send ? messages.platformAlert(row, device) : Mono.empty());
          }));
        });
      }));
  }

  static long lastTime(CloudAlarmRecord source) {
    return Math.max(source.getAlarmTime(), Objects.requireNonNullElse(source.getLastAlarmTime(), source.getAlarmTime()));
  }
  static String reason(CloudAlarmRecord source) {
    return source.getActualDesc() == null || source.getActualDesc().isBlank() ? source.getDescription() : source.getActualDesc();
  }
}
