package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 同一设备当前责任客户唯一，变更在同一事务结束旧关系并建新关系；通知联系人不隐含门户授权。 */
@Service
@RequiredArgsConstructor
public class FacilityBindings {

  private final BindingStore bindings;
  private final DeviceStore deviceStore;
  private final FacilityDevices devices;
  private final FacilityDirectory directory;
  private final FacilityScope scope;
  private final FacilityRecords records;

  @Transactional
  public Mono<BindingEntity> bind(String deviceId, BindingInput input) {
    return devices.writable(deviceId).flatMap(device -> {
      require(device.getVersion().equals(input.getVersion()), "conflict");
      FacilityDevices.requireActive(device);
      return directory
        .space(device.getProjectId(), input.getSpaceId())
        .thenMany(
          directory.customers(device.getProjectId(), input.getSpaceId())
        )
        .filter(
          c ->
            c.getId().equals(input.getCustomerId()) &&
            c.getSpaceIds().contains(input.getSpaceId())
        )
        .next()
        .switchIfEmpty(
          Mono.error(
            new org.hswebframework.web.exception.BusinessException(
              "error.facility.customer_invalid",
              400
            )
          )
        )
        .flatMap(customer ->
          scope.current().flatMap(c -> {
            long now = System.currentTimeMillis();
            var row = FacilityScope.initialize(new BindingEntity(), c);
            FacilityScope.inherit(row, device);
            row.setDeviceId(deviceId);
            row.setCustomerId(customer.getId());
            row.setCustomerName(customer.getName());
            row.setUserId(customer.getUserId());
            row.setSpaceId(input.getSpaceId());
            row.setStartTime(now);
            var contacts = new ArrayList<Contact>();
            var primary = new Contact();
            primary.setUserId(customer.getUserId());
            primary.setName(customer.getName());
            primary.setTelephone(customer.getTelephone());
            primary.setSmsEnabled(true);
            primary.setPortalEnabled(customer.getUserId() != null);
            contacts.add(primary);
            for (var contact : input.getContacts()) {
              require(
                contact.getTelephone() != null &&
                  contact.getTelephone().matches("[+0-9 -]{5,32}"),
                "invalid_contact"
              );
              require(
                !Boolean.TRUE.equals(contact.getPortalEnabled()) ||
                  (Objects.equals(customer.getUserId(), contact.getUserId()) &&
                    customer.getUserId() != null),
                "contact_portal_forbidden"
              );
              contacts.add(contact);
            }
            row.setContacts(contacts);
            return records
              .update(deviceStore, device, device.getVersion())
              .then(
                bindings
                  .createUpdate()
                  .set("endTime", now)
                  .set("endReason", input.getReason())
                  .where("tenantId", c.tenantId())
                  .and("applicationId", c.applicationId())
                  .and("deviceId", deviceId)
                  .isNull("endTime")
                  .execute()
              )
              .then(bindings.insert(row))
              .then(
                records.log(
                  device,
                  "binding",
                  "bind",
                  null,
                  customer.getId(),
                  input.getReason(),
                  List.of()
                )
              )
              .thenReturn(row);
          })
        );
    });
  }

  @Transactional
  public Mono<Void> end(String deviceId, long version, String reason) {
    return devices.writable(deviceId).flatMap(device -> {
      require(device.getVersion() == version, "conflict");
      return records
        .update(deviceStore, device, version)
        .then(scope.current())
        .flatMap(c ->
          bindings
            .createUpdate()
            .set("endTime", System.currentTimeMillis())
            .set("endReason", reason)
            .where("tenantId", c.tenantId())
            .and("applicationId", c.applicationId())
            .and("deviceId", deviceId)
            .isNull("endTime")
            .execute()
        )
        .then(
          records.log(device, "binding", "end", null, null, reason, List.of())
        );
    });
  }

  public Flux<BindingEntity> history(String deviceId) {
    return devices
      .read(deviceId)
      .flatMapMany(device ->
        bindings
          .createQuery()
          .where("tenantId", device.getTenantId())
          .and("applicationId", device.getApplicationId())
          .and("deviceId", deviceId)
          .and("projectId", device.getProjectId())
          .and("deleted", false)
          .orderBy(
            org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc(
              "startTime"
            )
          )
          .fetch()
      );
  }

  public Mono<BindingEntity> active(FacilityEntity device) {
    return bindings
      .createQuery()
      .where("tenantId", device.getTenantId())
      .and("applicationId", device.getApplicationId())
      .and("deviceId", device.getId())
      .and("projectId", device.getProjectId())
      .and("deleted", false)
      .isNull("endTime")
      .fetchOne();
  }
}
