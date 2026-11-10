package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 标准与机房的受控配置入口；设备成员必须属于同一业务项目。 */
@Service
@RequiredArgsConstructor
public class FacilityConfiguration {

  private final StandardStore standards;
  private final GroupStore groups;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityDirectory directory;
  private final FacilityDevices devices;
  private final FacilityFiles files;

  @Transactional
  public Mono<StandardEntity> standard(StandardEntity input) {
    require(
      input.getName() != null &&
        !input.getName().isBlank() &&
        input.getKind() != null &&
        Set.of("inspection", "maintenance").contains(input.getKind()),
      "invalid_standard"
    );
    FacilityRules.standard(input.getCheckItems());
    return files
      .validate(input.getAttachments(), input.getProjectId())
      .then(directory.project(input.getProjectId()))
      .then(
        records.assertProject(input.getProjectId(), CrudAssetPermission.save)
      )
      .then(scope.current())
      .flatMap(c -> {
        Mono<StandardEntity> old =
          input.getId() == null
            ? Mono.just(FacilityScope.initialize(new StandardEntity(), c))
            : records.read(
                standards,
                input.getId(),
                FacilityAssetType.facilityStandard,
                CrudAssetPermission.save
              );
        return old.flatMap(row -> {
          boolean create = input.getId() == null;
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
          row.setKind(input.getKind());
          row.setCheckItems(input.getCheckItems());
          row.setInstruction(input.getInstruction());
          row.setAttachments(input.getAttachments());
          row.setEnabled(input.getEnabled());
          return create
            ? records.insert(standards, row, FacilityAssetType.facilityStandard)
            : records.update(standards, row, v);
        });
      });
  }

  @Transactional
  public Mono<GroupEntity> group(GroupEntity input) {
    require(
      input.getName() != null &&
        !input.getName().isBlank() &&
        input.getDeviceIds().size() <= 1000,
      "invalid_group"
    );
    return directory
      .project(input.getProjectId())
      .then(
        records.assertProject(input.getProjectId(), CrudAssetPermission.save)
      )
      .thenMany(
        Flux.fromIterable(input.getDeviceIds())
          .concatMap(devices::read)
          .doOnNext(d -> {
            require(
              input.getProjectId().equals(d.getProjectId()),
              "project_mismatch"
            );
            require(FacilityDevices.workEnabled(d), "device_work_disabled");
          })
      )
      .then(scope.current())
      .flatMap(c -> {
        boolean create = input.getId() == null;
        return (
          create
            ? Mono.just(FacilityScope.initialize(new GroupEntity(), c))
            : records.read(
                groups,
                input.getId(),
                FacilityAssetType.facilityGroup,
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
          row.setDeviceIds(input.getDeviceIds().stream().distinct().toList());
          row.setLocation(input.getLocation());
          row.setSystemFilter(input.getSystemFilter());
          row.setCategoryFilter(input.getCategoryFilter());
          return create
            ? records.insert(groups, row, FacilityAssetType.facilityGroup)
            : records.update(
                groups,
                row,
                v,
                FacilityRecords.nullFields(row, "location")
              );
        });
      });
  }
}
