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
  private final FacilityAllocations allocations;

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
    return allocations.refresh().then(manualJob(projectId)).flatMap(this::run);
  }

  /** 手动同步不启用/重置已有的定时任务，也不释放其他执行者的租约。 */
  private Mono<JobEntity> manualJob(String projectId) {
    return directory.project(projectId).flatMap(project -> records.assertProject(projectId, CrudAssetPermission.save)
      .then(scope.current()).flatMap(context -> {
        String id = FacilityRecords.stableId(context.tenantId(), context.applicationId(), projectId, "sync");
        return jobs.findById(id).switchIfEmpty(Mono.defer(() -> {
          var row = FacilityScope.initialize(new JobEntity(), context);
          row.setId(id); row.setProjectId(projectId); row.setOrganizationId(projectId); row.setProjectName(project.getName());
          row.setName(project.getName()); row.setKind("sync"); row.setEnabled(false); row.setState("idle"); row.setLeaseUntil(0L);
          return jobs.insert(row).thenReturn(row);
        }));
      }));
  }

  public record SyncSummary(int inserted, int updated, int failed, int projects, List<String> failures) {}

  /** 同步范围来自当前调用人的可写项目和已保存配置，与资产列表查询条件无关。 */
  public Mono<SyncSummary> synchronizeConfigured() {
    return allocations.refresh().thenMany(directory.projects())
      .concatMap(project -> records.assertProject(project.getId(), CrudAssetPermission.save).thenReturn(project)
        .onErrorResume(org.hswebframework.web.authorization.exception.AccessDenyException.class, error -> Mono.empty()))
      .concatMap(project -> allocations.configured(project.getId()).hasElements().filter(Boolean::booleanValue)
        .flatMap(yes -> manualJob(project.getId()).flatMap(this::run))
        .onErrorResume(error -> {
          var row = new JobEntity(); row.setProjectName(project.getName()); row.setFailed(1);
          row.setFailureReason(FacilityMeterDrafts.error(error)); return Mono.just(row);
        }))
      .collectList().map(rows -> new SyncSummary(
        rows.stream().mapToInt(row -> Objects.requireNonNullElse(row.getInserted(), 0)).sum(),
        rows.stream().mapToInt(row -> Objects.requireNonNullElse(row.getUpdated(), 0)).sum(),
        rows.stream().mapToInt(row -> Objects.requireNonNullElse(row.getFailed(), 0)).sum(), rows.size(),
        rows.stream().filter(row -> row.getFailureReason() != null)
          .map(row -> row.getProjectName() + ": " + row.getFailureReason()).toList()))
      .as(org.jetlinks.core.trace.MonoTracer.create("property.facility.devices.sync-configured"));
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
        return allocations.sources()
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
    String state = source.getState() == null ? "unknown" : source.getState().name();
    if (!Objects.equals(state, row.getSourceState()) || row.getSourceStateSince() == null) row.setSourceStateSince(now);
    row.setSourceState(state);
    row.setLastSeenTime(now);
    row.setCandidateProjectIds(projects);
    boolean existing =
      row.getProjectId() != null && projects.contains(row.getProjectId());
    row.setSyncState(
      !projects.isEmpty() && (Boolean.TRUE.equals(row.getProjectConfirmed()) || existing && projects.size() == 1)
        ? "active"
        : "pending-project"
    );
    if (Boolean.TRUE.equals(row.getProjectConfirmed())) row.setPendingProjectId(null);
    else if (!existing && projects.size() == 1) row.setPendingProjectId(
      projects.get(0)
    );
    if ("notActive".equals(row.getSourceState())) row.setSyncState("inactive");
  }

  public record Source(String id, String name, List<DeviceInfo> devices) {}
}
