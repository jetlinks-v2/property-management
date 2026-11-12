package org.jetlinks.pro.property.facility.service;

import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.notify.command.SendUserNotificationCommand;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.Contact;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.notify.cmd.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 持久化通知发件箱；短信提交不代表送达，每次发送复核当前设备责任客户。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FacilityNotifications {

  private final FacilityDevices deviceManager;
  private final EnergyTargetStore targets;
  private final AlertStore alertStore;
  private final FacilityReadings readings;
  private final FacilityDirectory directory;
  private final FacilityRecords records;
  private final NotificationStore store;
  private final BindingStore bindings;
  private final FacilityBindings bindingManager;
  private final DeviceStore devices;
  private final RuleStore rules;
  private final FacilityExecution execution;
  private final FacilityEmployeeMessages employeeMessages;

  public Mono<Void> enqueue(
    AlertEntity alert,
    RuleEntity rule,
    DeviceEntity device
  ) {
    return employeeMessages.alert(alert, rule, device);
  }

  /** Project plans notify the configured employee, never an arbitrary meter owner. */
  public Mono<Void> enqueueTarget(
    AlertEntity alert,
    RuleEntity rule,
    EnergyTargetEntity target
  ) {
    return employeeMessages.target(alert, rule, target);
  }

  private Mono<Void> authorize(
    NotificationEntity row,
    org.jetlinks.pro.assets.crud.CrudAssetPermission action
  ) {
    if (row.getTargetId() != null) return records
      .read(
        targets,
        row.getTargetId(),
        org.jetlinks.pro.property.facility.asset.FacilityAssetType.facilityTarget,
        action
      )
      .flatMap(target -> records.child(row, target))
      .then();
    return (
      action == org.jetlinks.pro.assets.crud.CrudAssetPermission.save
        ? deviceManager.writable(row.getDeviceId())
        : deviceManager.read(row.getDeviceId())
    )
      .flatMap(device -> records.child(row, device))
      .then();
  }

  private NotificationEntity entry(
    AlertEntity alert,
    RuleEntity rule,
    DeviceEntity device,
    BindingEntity binding,
    Contact contact,
    String channel
  ) {
    var row = FacilityScope.initialize(
      new NotificationEntity(),
      new FacilityContext(
        alert.getTenantId(),
        alert.getApplicationId(),
        rule.getCreatorId(),
        rule.getCreatorName()
      )
    );
    FacilityScope.inherit(row, alert);
    String recipient = channel.equals("sms")
      ? contact.getTelephone()
      : contact.getUserId();
    row.setId(
      FacilityRecords.stableId(
        alert.getId(),
        recipient,
        channel,
        String.valueOf(alert.getNotificationSequence())
      )
    );
    row.setEventId(alert.getId());
    row.setDeviceId(device.getId());
    row.setBindingId(binding == null ? null : binding.getId());
    row.setUserId(contact.getUserId());
    row.setTelephone(contact.getTelephone());
    row.setRecipientKey(recipient);
    row.setChannel(channel);
    row.setSequence(alert.getNotificationSequence());
    row.setTitle(rule.getName());
    row.setMessage(
      LocaleUtils.resolveMessage(
        "message.facility.alert",
        "{0}：{1}，当前{2}{3}，请查看设备能源提醒。",
        device.getName(),
        rule.getName(),
        alert.getValue(),
        Objects.toString(alert.getUnit(), "")
      )
    );
    row.setState(channel.equals("h5") ? "generated" : "pending");
    row.setAttempts(0);
    row.setNextAttemptTime(System.currentTimeMillis());
    row.setNotifierId(rule.getSmsNotifierId());
    row.setTemplateId(rule.getSmsTemplateId());
    return row;
  }

  public Mono<Void> poll() {
    long now = System.currentTimeMillis();
    // 历史客户/短信发件箱也必须停止发送，保留可审计的停发原因。
    return store.createUpdate().set("state", "cancelled").set("failureReason", "employee_channel_only")
      .in("channel", List.of("sms", "h5")).in("state", List.of("pending", "failed")).execute()
      .then(store
      .createUpdate()
      .set("state", "uncertain")
      .set("failureReason", "submission_result_unknown")
      .where("state", "sending")
      .lte("nextAttemptTime", now)
      .execute())
      .thenMany(
        store
          .createQuery()
          .in("state", List.of("pending"))
          .lte("nextAttemptTime", now)
          .orderBy(
            org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(
              "nextAttemptTime"
            )
          )
          .fetch()
          .take(100)
      )
      .concatMap(row ->
        execution
          .asOwner(row, () -> send(row, now))
          .onErrorResume(e -> {
            log.warn(
              "Facility notification failed: id={}, type={}",
              row.getId(),
              e.getClass().getSimpleName()
            );
            return e instanceof
              org.hswebframework.web.exception.BusinessException
              ? fail(row, now, e.getClass().getSimpleName())
              : store
                  .createUpdate()
                  .set("state", "uncertain")
                  .set("failureReason", e.getClass().getSimpleName())
                  .where("id", row.getId())
                  .and("state", "sending")
                  .execute()
                  .then();
          })
      )
      .then();
  }

  private Mono<Void> fail(NotificationEntity row, long now, String reason) {
    int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
    return store
      .createUpdate()
      .set("state", attempts >= 8 ? "failed" : "pending")
      .set("failureReason", reason)
      .set(
        "nextAttemptTime",
        now + Math.min(3600000, 30000L * (1L << Math.min(attempts, 7)))
      )
      .where("id", row.getId())
      .execute()
      .then();
  }

  private Mono<Void> eligible(NotificationEntity row) {
    return alertStore
      .findById(row.getEventId())
      .filter(
        alert ->
          Objects.equals(alert.getTenantId(), row.getTenantId()) &&
          Objects.equals(alert.getApplicationId(), row.getApplicationId()) &&
          Objects.equals(alert.getProjectId(), row.getProjectId()) &&
          Objects.equals(alert.getNotificationSequence(), row.getSequence()) &&
          !Set.of("closed", "recovered").contains(alert.getState())
      )
      .flatMap(alert ->
        rules
          .findById(alert.getRuleId())
          .filter(
            rule ->
              !Boolean.TRUE.equals(rule.getDeleted()) &&
              Boolean.TRUE.equals(rule.getEnabled())
          )
          .flatMap(rule -> {
            if (!"balance".equals(rule.getRuleType())) return Mono.just(true);
            long now = System.currentTimeMillis();
            return readings
              .series(
                row.getDeviceId(),
                rule.getPropertyId(),
                now - rule.getStaleAfterSeconds() * 1000,
                now + 1
              )
              .flatMapMany(Flux::fromIterable)
              .filter(
                reading ->
                  reading.getValue() != null &&
                  reading.getObservedTime() >=
                    now - rule.getStaleAfterSeconds() * 1000 &&
                  reading.getObservedTime() <= now
              )
              .sort(
                Comparator.comparing(ReadingEntity::getObservedTime).reversed()
              )
              .next()
              .filter(
                reading ->
                  reading.getValue().compareTo(rule.getThreshold()) <= 0
              )
              .map(reading -> true);
          })
      )
      .switchIfEmpty(
        Mono.error(
          new org.hswebframework.web.authorization.exception.AccessDenyException()
        )
      )
      .then(eligibleRecipient(row));
  }

  private Mono<Void> eligibleRecipient(NotificationEntity row) {
    if (row.getTargetId() != null) return targets
      .findById(row.getTargetId())
      .filter(
        target ->
          !Boolean.TRUE.equals(target.getDeleted()) &&
          Objects.equals(target.getTenantId(), row.getTenantId()) &&
          Objects.equals(target.getApplicationId(), row.getApplicationId()) &&
          Objects.equals(target.getProjectId(), row.getProjectId())
      )
      .flatMap(target ->
        rules
          .createQuery()
          .where("tenantId", row.getTenantId())
          .and("applicationId", row.getApplicationId())
          .and("projectId", row.getProjectId())
          .and("ruleType", "plan")
          .and("deleted", false)
          .and("enabled", true)
          .fetch()
          .filter(
            rule ->
              Objects.equals(
                FacilityRecords.stableId(rule.getId(), target.getId()),
                row.getEventId()
              ) && Objects.equals(rule.getNotifyUserId(), row.getUserId())
          )
          .next()
      )
      .flatMap(rule ->
        directory
          .employee(row.getUserId())
          .filter(
            employee ->
              !"sms".equals(row.getChannel()) ||
              Objects.equals(employee.getTelephone(), row.getTelephone())
          )
      )
      .switchIfEmpty(
        Mono.error(
          new org.hswebframework.web.authorization.exception.AccessDenyException()
        )
      )
      .then();
    return devices
      .findById(row.getDeviceId())
      .filter(
        d ->
          d.getTenantId().equals(row.getTenantId()) &&
          d.getApplicationId().equals(row.getApplicationId()) &&
          "active".equals(d.getSyncState())
      )
      .flatMap(device -> {
        if ("inside".equals(row.getChannel())) return Mono.just(device)
          .filter(d ->
            Objects.equals(d.getResponsibleUserId(), row.getUserId())
          )
          .map(d -> true);
        return bindingManager
          .active(device)
          .filter(
            b ->
              b.getId().equals(row.getBindingId()) &&
              b
                .getContacts()
                .stream()
                .anyMatch(c ->
                  "sms".equals(row.getChannel())
                    ? Boolean.TRUE.equals(c.getSmsEnabled()) &&
                      Objects.equals(c.getTelephone(), row.getTelephone())
                    : Boolean.TRUE.equals(c.getPortalEnabled()) &&
                      Objects.equals(c.getUserId(), row.getUserId())
                )
          )
          .map(b -> true);
      })
      .filter(Boolean.TRUE::equals)
      .switchIfEmpty(
        Mono.error(
          new org.hswebframework.web.authorization.exception.AccessDenyException()
        )
      )
      .then();
  }

  private Mono<Void> send(NotificationEntity row, long now) {
    if (!"inside".equals(row.getChannel())) return retireChannel(row);
    // A stale recipient/asset cancels before dispatch. A provider rejection is a
    // failed delivery instead, so configuration can be corrected and retried.
    return authorize(row, org.jetlinks.pro.assets.crud.CrudAssetPermission.read)
      .then(eligible(row))
      .thenReturn(true)
      .onErrorResume(
        org.hswebframework.web.authorization.exception.AccessDenyException.class,
        e -> store.createUpdate()
          .set("state", "cancelled")
          .set("failureReason", "eligibility_permission_denied")
          .where("id", row.getId())
          .and("state", "pending")
          .and("nextAttemptTime", row.getNextAttemptTime())
          .execute()
          .thenReturn(false)
      )
      .filter(Boolean.TRUE::equals)
      .flatMap(allowed ->
        store
          .createUpdate()
          .set("state", "sending")
          .setNull("failureReason")
          .set("attempts", row.getAttempts() + 1)
          .set("nextAttemptTime", now + 120000)
          .where("id", row.getId())
          .and("state", row.getState())
          .and("nextAttemptTime", row.getNextAttemptTime())
          .execute()
      )
      .filter(n -> n == 1)
      .flatMap(n -> {
        row.setAttempts(row.getAttempts() + 1);
        if (
          "inside".equals(row.getChannel())
        ) return sendInside(row)
          .then(
            store
              .createUpdate()
              .set("state", "sent")
              .where("id", row.getId())
              .execute()
          )
          .then();
        FacilityScope.require(
          row.getNotifierId() != null && row.getTemplateId() != null,
          "sms_configuration_required"
        );
        var command = new SendNotifyCommand()
          .withNotifierId(row.getNotifierId())
          .withTemplateId(row.getTemplateId())
          .withBizType("facility-energy")
          .withBizId(row.getId())
          .disableRetry()
          .withVariable("telephone", row.getTelephone())
          .withVariable("phone", row.getTelephone())
          .withVariable("message", row.getMessage());
        return CommandServices.getReactive(SdkServices.notifyService)
          .flatMapMany(s -> s.execute(command))
          .collectList()
          .flatMap(results -> {
            FacilityScope.require(
              !results.isEmpty() &&
                results
                  .stream()
                  .allMatch(
                    r ->
                      r.getState() == NotifyResult.STATE_SUCCESS ||
                      r.getState() == NotifyResult.STATE_QUEUED
                  ),
              "sms_rejected"
            );
            String callId = results
              .stream()
              .map(NotifyResult::getCallId)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(row.getId());
            return store
              .createUpdate()
              .set("state", "submitted")
              .set("providerMessageId", callId)
              .where("id", row.getId())
              .execute()
              .then();
          });
      })
      .timeout(Duration.ofSeconds(30))
      .onErrorResume(
        org.hswebframework.web.authorization.exception.AccessDenyException.class,
        e -> {
          log.warn("Facility notification delivery denied: id={}", row.getId(), e);
          return store
            .createUpdate()
            .set("state", "failed")
            .set("failureReason", "delivery_permission_denied")
            .where("id", row.getId())
            .and("state", "sending")
            .execute()
            .then();
        }
      );
  }

  /** Public Cloud command retains the creator's scoped identity and recipient checks. */
  Mono<Void> sendInside(NotificationEntity row) {
    return new SendUserNotificationCommand()
      .withUserId(row.getUserId())
      .withChannelProvider("inside-mail")
      .withTopicProvider(FacilityNotifySubscriberProvider.ID)
      .withTopicName("Facility energy")
      .withSubscribeId(FacilityNotifySubscriberProvider.ID)
      .withCode("alert")
      .withDataId(row.getId())
      .withNotifyTime(row.getCreateTime())
      .withMessage(row.getMessage())
      .withDetail(
        Map.of(
          "deviceId",
          Objects.toString(row.getDeviceId(), ""),
          "targetId",
          Objects.toString(row.getTargetId(), ""),
          "applicationId",
          row.getApplicationId()
        )
      )
      .execute();
  }

  public Mono<Void> receipt(String callId, boolean delivered, String reason) {
    return store
      .createUpdate()
      .set("state", delivered ? "delivered" : "failed")
      .set("deliveredTime", delivered ? System.currentTimeMillis() : null)
      .set("failureReason", reason)
      .where("providerMessageId", callId)
      .and("channel", "sms")
      .and("state", "submitted")
      .execute()
      .then();
  }

  public Mono<Void> retry(String id) {
    return store
      .findById(id)
      .switchIfEmpty(
        Mono.error(new org.hswebframework.web.exception.NotFoundException())
      )
      .flatMap(row ->
        authorize(row, org.jetlinks.pro.assets.crud.CrudAssetPermission.save)
          .then(Mono.defer(() -> !"inside".equals(row.getChannel()) ? retireChannel(row) : eligible(row)
          .then(
            store
              .createUpdate()
              .set("state", "pending")
              .set("attempts", 0)
              .set("nextAttemptTime", System.currentTimeMillis())
              .where("id", id)
              .in("state", List.of("failed", "pending"))
              .execute().then()
          )))
      )
      .then();
  }

  private Mono<Void> retireChannel(NotificationEntity row) {
    return store.createUpdate().set("state", "cancelled").set("failureReason", "employee_channel_only")
      .where("id", row.getId()).in("state", List.of("pending", "failed")).execute().then();
  }
}
