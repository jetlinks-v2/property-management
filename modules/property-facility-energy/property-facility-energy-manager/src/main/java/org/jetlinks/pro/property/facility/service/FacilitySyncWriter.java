package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 完整来源快照的事务写入owner；远程查询完成后才进入写事务。 */
@Service
@RequiredArgsConstructor
public class FacilitySyncWriter {

  private final FacilityScope scope;
  private final DeviceStore devices;
  private final JobStore jobs;
  private final FacilityRecords records;

  @Transactional
  public Mono<JobEntity> apply(
    JobEntity job,
    List<FacilitySync.Source> sources,
    long now
  ) {
    Map<String, DeviceInfo> byId = new LinkedHashMap<>();
    Map<String, List<String>> candidates = new HashMap<>();
    Map<String, String> names = new HashMap<>();
    for (var source : sources) {
      names.put(source.id(), source.name());
      for (var d : source.devices()) {
        byId.put(d.getId(), d);
        candidates
          .computeIfAbsent(d.getId(), k -> new ArrayList<>())
          .add(source.id());
      }
    }
    var selected = byId
      .values()
      .stream()
      .filter(d -> candidates.get(d.getId()).contains(job.getProjectId()))
      .toList();
    return scope.current().flatMap(c ->
      Flux.fromIterable(selected)
        .concatMap(d -> {
          String id = FacilityRecords.stableId(
            c.tenantId(),
            c.applicationId(),
            d.getId()
          );
          return devices
            .findById(id)
            .flatMap(row -> {
              long version = row.getVersion();
              FacilitySync.mergeSource(row, d, candidates.get(d.getId()), now);
              return records.update(devices, row, version);
            })
            .switchIfEmpty(
              Mono.defer(() -> {
                var row = FacilityScope.initialize(new DeviceEntity(), c);
                row.setId(id);
                row.setCloudDeviceId(d.getId());
                row.setProjectId(job.getProjectId());
                row.setOrganizationId(job.getProjectId());
                row.setProjectName(names.get(job.getProjectId()));
                row.setName(d.getName());
                FacilitySync.mergeSource(
                  row,
                  d,
                  candidates.get(d.getId()),
                  now
                );
                return records.insert(
                  devices,
                  row,
                  FacilityAssetType.facilityDevice
                );
              })
            );
        })
        .thenMany(
          devices
            .createQuery()
            .where("tenantId", c.tenantId())
            .and("applicationId", c.applicationId())
            .and("projectId", job.getProjectId())
            .and("deleted", false)
            .fetch()
        )
        .concatMap(row -> {
          if (Boolean.TRUE.equals(row.getProjectConfirmed()) && candidates.containsKey(row.getCloudDeviceId())) return Mono.empty();
          if (
            selected
              .stream()
              .anyMatch(d -> d.getId().equals(row.getCloudDeviceId()))
          ) return Mono.empty();
          // Loss of source visibility is not proof of deletion: retain record and pause, require explicit reconciliation.
          long v = row.getVersion();
          row.setSyncState("source-unavailable");
          row.setCandidateProjectIds(
            candidates.getOrDefault(row.getCloudDeviceId(), List.of())
          );
          return records.update(devices, row, v).then();
        })
        .then(
          jobs
            .createUpdate()
            .set("state", "success")
            .set("lastSuccessTime", now)
            .set("processed", selected.size())
            .set("nextRunTime", now + 3600000)
            .set("leaseUntil", 0L)
            .setNull("failureReason")
            .where("id", job.getId())
            .execute()
        )
        .then(jobs.findById(job.getId()))
    );
  }
}
