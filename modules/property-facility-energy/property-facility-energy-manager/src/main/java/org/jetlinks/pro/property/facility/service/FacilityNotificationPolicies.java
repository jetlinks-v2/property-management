package org.jetlinks.pro.property.facility.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class FacilityNotificationPolicies {
  private final NotificationPolicyStore store;
  private final FacilityScope scope;
  private final FacilityDirectory directory;
  private final FacilityRecords records;

  public Mono<Boolean> enabled(FacilityEntity parent, String category) {
    return store.findById(id(parent)).map(row -> switch (category) {
      case "task" -> !Boolean.FALSE.equals(row.getTaskEnabled());
      case "fault" -> !Boolean.FALSE.equals(row.getFaultEnabled());
      default -> !Boolean.FALSE.equals(row.getAlertEnabled());
    }).defaultIfEmpty(true);
  }

  public Mono<NotificationPolicyEntity> read(String projectId) {
    return directory.project(projectId).then(scope.current()).flatMap(context -> {
      var row = FacilityScope.initialize(new NotificationPolicyEntity(), context);
      row.setProjectId(projectId); row.setOrganizationId(projectId); row.setId(id(row));
      return store.findById(row.getId()).defaultIfEmpty(row);
    });
  }

  @Transactional
  public Mono<NotificationPolicyEntity> save(NotificationPolicyEntity input) {
    FacilityScope.require(input.getProjectId() != null && input.getTaskEnabled() != null && input.getFaultEnabled() != null && input.getAlertEnabled() != null, "invalid_state");
    return records.assertProject(input.getProjectId(), CrudAssetPermission.save).then(read(input.getProjectId())).flatMap(row -> {
      FacilityScope.require(Objects.equals(row.getVersion(), input.getVersion()), "conflict");
      row.setTaskEnabled(input.getTaskEnabled()); row.setFaultEnabled(input.getFaultEnabled()); row.setAlertEnabled(input.getAlertEnabled());
      return store.findById(row.getId()).hasElement().flatMap(exists -> exists ? records.update(store, row, row.getVersion()) : store.insert(row).thenReturn(row));
    });
  }

  private String id(FacilityEntity parent) { return FacilityRecords.stableId(parent.getTenantId(), parent.getApplicationId(), parent.getProjectId(), "notification-policy"); }
}
