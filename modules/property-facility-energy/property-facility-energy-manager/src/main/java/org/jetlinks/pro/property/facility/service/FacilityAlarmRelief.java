package org.jetlinks.pro.property.facility.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 员工主动解除 SaaS 设备告警，目标只能来自当前可写且仍绑定的业务设备。
 * 远程副作用不参与本地事务；平台确认后由 writer 原子同步状态与审计，不改变本地人工处置状态。
 * @see FacilityCloudAlarms#relieve(CloudAlarmRecord, String)
 */
@Service
@RequiredArgsConstructor
public class FacilityAlarmRelief {
  private final AlertStore alerts;
  private final FacilityDevices devices;
  private final FacilityRecords records;
  private final FacilityCloudAlarms cloud;
  private final FacilitySaasAlarmWriter writer;

  public Mono<AlertEntity> relieve(String id, long version, String comment) {
    return Mono.defer(() -> {
      FacilityScope.require(comment != null && !comment.isBlank() && comment.length() <= 4000, "reason_required");
      String reason = comment.trim();
      return alerts.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(row -> {
        FacilityScope.require("saas".equals(row.getSource()) && row.getTargetId() == null
          && row.getDeviceId() != null && row.getSourceRecordId() != null, "invalid_alarm_source");
        return devices.writable(row.getDeviceId()).flatMap(devices::requireBound).flatMap(device ->
          records.child(row, device).then(Mono.defer(() -> {
            FacilityScope.require(row.getVersion() == version, "conflict");
            return source(device, row).flatMap(before -> {
              FacilityScope.require(Objects.equals(row.getFirstTime(), before.getAlarmTime()), "source_changed");
              if ("normal".equals(before.stateValue())) return writer.accept(device, before).then(alerts.findById(id));
              FacilityScope.require("warning".equals(before.stateValue()), "invalid_alarm_source");
              return cloud.relieve(before, reason).then(Mono.defer(() -> source(device, row))).flatMap(after -> {
                // 命令返回不等于状态已经稳定；若已再次触发或仍告警，不能把本地强制改成已解除。
                FacilityScope.require(Objects.equals(before.getAlarmTime(), after.getAlarmTime()), "source_changed");
                FacilityScope.require("normal".equals(after.stateValue()), "alarm_relief_unconfirmed");
                // Topic 可能先写入解除状态；只重试本地新事务，远程命令不能随之重发。
                return Mono.defer(() -> writer.acceptRelief(device, after, row, reason))
                  .retryWhen(reactor.util.retry.Retry.max(2)
                    .filter(error -> error instanceof org.hswebframework.web.exception.BusinessException conflict
                      && "error.facility.conflict".equals(conflict.getI18nCode()))
                    .onRetryExhaustedThrow((retry, signal) -> signal.failure()))
                  .then(alerts.findById(id));
              });
            });
          })));
      });
    });
  }

  private Mono<CloudAlarmRecord> source(DeviceEntity device, AlertEntity row) {
    return cloud.records(device.getCloudDeviceId()).flatMapMany(reactor.core.publisher.Flux::fromIterable)
      .filter(source -> row.getSourceRecordId().equals(source.getId()))
      .singleOrEmpty().switchIfEmpty(Mono.error(new NotFoundException()))
      .doOnNext(source -> FacilityScope.require(source.belongsTo(device.getCloudDeviceId())
        && "device".equals(source.getTargetType()) && device.getCloudDeviceId().equals(source.getTargetId())
        && row.getSourceConfigId() != null && row.getSourceConfigId().equals(source.getAlarmConfigId()), "invalid_alarm_source"));
  }
}
