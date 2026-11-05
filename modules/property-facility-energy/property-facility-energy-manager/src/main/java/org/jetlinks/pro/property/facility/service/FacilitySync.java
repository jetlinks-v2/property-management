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

/** 完整来源快照先校验再落库；来源缺失只暂停新业务，不删除历史，不因查询失败覆盖台账。 */
@Service
@RequiredArgsConstructor
public class FacilitySync {

  private final FacilitySyncWriter writer;
  private final FacilityCloud cloud;
  private final FacilityDirectory directory;
  private final FacilityScope scope;
  private final DeviceStore devices;
  private final JobStore jobs;
  private final FacilityRecords records;

  public Mono<JobEntity> configure(String projectId, boolean enabled) {
    return directory.project(projectId).flatMap(p ->
      records
        .assertProject(projectId, CrudAssetPermission.save)
        .then(scope.current())
        .flatMap(c -> {
          String id = FacilityRecords.stableId(
            c.tenantId(),
            c.applicationId(),
            projectId,
            "sync"
          );
          return jobs
            .findById(id)
            .defaultIfEmpty(FacilityScope.initialize(new JobEntity(), c))
            .flatMap(row -> {
              require(
                row.getLeaseUntil() == null ||
                  row.getLeaseUntil() <= System.currentTimeMillis(),
                "sync_busy"
              );
              row.setId(id);
              row.setProjectId(projectId);
              row.setOrganizationId(projectId);
              row.setProjectName(p.getName());
              row.setName(p.getName());
              row.setKind("sync");
              row.setEnabled(enabled);
              row.setState("idle");
              row.setNextRunTime(System.currentTimeMillis());
              row.setLeaseUntil(0L);
              row.setCreatorId(c.userId());
              return jobs.save(row).thenReturn(row);
            });
        })
    );
  }

  public Mono<JobEntity> synchronize(String projectId) {
    return configure(projectId, true).flatMap(this::run);
  }

  public Mono<JobEntity> run(JobEntity job) {
    long now = System.currentTimeMillis();
    return records
      .assertProject(job.getProjectId(), CrudAssetPermission.save)
      .then(
        jobs
          .createUpdate()
          .set("state", "running")
          .set("leaseUntil", now + 600000)
          .set("lastStartTime", now)
          .where("id", job.getId())
          .lte("leaseUntil", now)
          .execute()
      )
      .flatMap(count -> {
        require(count == 1, "sync_busy");
        // A failed lease acquisition never releases another worker's lease.
        return directory
          .projects()
          .take(1001)
          .collectList()
          .doOnNext(projects -> require(projects.size() <= 1000, "too_many"))
          .flatMapMany(Flux::fromIterable)
          .concatMap(p ->
            cloud
              .snapshot(p.getId())
              .map(rows -> new Source(p.getId(), p.getName(), rows))
          )
          .collectList()
          .timeout(java.time.Duration.ofMinutes(8))
          .flatMap(sources -> writer.apply(job, sources, now))
          .onErrorResume(error ->
            jobs
              .createUpdate()
              .set("state", "failed")
              .set("failureReason", error.getClass().getSimpleName())
              .set("leaseUntil", 0L)
              .set("nextRunTime", now + 60000)
              .where("id", job.getId())
              .and("lastStartTime", now)
              .execute()
              .then(Mono.error(error))
          );
      });
  }

  static void mergeSource(
    DeviceEntity row,
    DeviceInfo source,
    List<String> projects,
    long now
  ) {
    row.setSourceName(source.getName());
    row.setSourceCode(source.getId());
    row.setProductId(source.getProductId());
    row.setProductName(source.getProductName());
    row.setSourceState(
      source.getState() == null ? "unknown" : source.getState().name()
    );
    row.setLastSeenTime(now);
    row.setCandidateProjectIds(projects);
    boolean existing =
      row.getProjectId() != null && projects.contains(row.getProjectId());
    row.setSyncState(
      existing &&
        (projects.size() == 1 || Boolean.TRUE.equals(row.getProjectConfirmed()))
        ? "active"
        : "pending-project"
    );
    if (!existing && projects.size() == 1) row.setPendingProjectId(
      projects.get(0)
    );
    if ("notActive".equals(row.getSourceState())) row.setSyncState("inactive");
  }

  public record Source(String id, String name, List<DeviceInfo> devices) {}
}
