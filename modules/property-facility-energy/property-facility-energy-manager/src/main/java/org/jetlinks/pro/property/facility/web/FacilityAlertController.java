package org.jetlinks.pro.property.facility.web;

import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;

/** 告警和通知审计的读取范围关联所属设施，重试再次核对当前客户绑定。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/alerts")
@Resource(id = "facility-alert", name = "预警通知")
public class FacilityAlertController {

  private final AlertStore alerts;
  private final NotificationStore notifications;
  private final FacilityAlerts manager;
  private final FacilityNotifications notifier;
  private final FacilityQueries queries;
  private final AlertEpisodeStore episodes;
  private final ProcessStore processes;

  public record Action(long version, String state, String comment) {}

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<AlertEntity>> query(@RequestBody QueryParamEntity q) {
    return queries.page(
      alerts,
      q.clone().toNestQuery().notNull("deviceId").getParam(),
      FacilityAssetType.facilityDevice,
      "deviceId"
    );
  }

  @GetMapping("/{id}") @QueryAction
  public Mono<AlertEntity> read(@PathVariable String id) { return manager.read(id); }

  @PostMapping("/history/_query") @QueryAction
  public Mono<PagerResult<AlertEpisodeEntity>> history(@RequestBody QueryParamEntity query) {
    return queries.page(episodes, query.clone().toNestQuery().notNull("deviceId").getParam(), FacilityAssetType.facilityDevice, "deviceId");
  }

  @GetMapping("/{id}/history") @QueryAction
  public Mono<PagerResult<AlertEpisodeEntity>> episodes(@PathVariable String id, @RequestParam(defaultValue = "0") int pageIndex) {
    return read(id).flatMap(row -> {
      // 历史快照的episodeId指向当前事件，分页仍与同一设备/计划资产范围相交。
      String current = row instanceof AlertEpisodeEntity ? row.getEpisodeId() : row.getId();
      var q = QueryParamEntity.newQuery().is("episodeId", current).orderByDesc("firstTime").getParam();
      q.setPageIndex(Math.max(0, pageIndex)); q.setPageSize(15);
      return queries.page(episodes, q, row.getTargetId() == null ? FacilityAssetType.facilityDevice : FacilityAssetType.facilityTarget,
        row.getTargetId() == null ? "deviceId" : "targetId");
    });
  }

  @GetMapping("/{id}/processes") @QueryAction
  public Mono<PagerResult<ProcessEntity>> processes(@PathVariable String id, @RequestParam(defaultValue = "0") int pageIndex) {
    return read(id).flatMap(row -> {
      String current = row instanceof AlertEpisodeEntity ? row.getEpisodeId() : row.getId();
      var q = QueryParamEntity.newQuery().is("targetId", current).and("targetType", "alert").orderByDesc("createTime").getParam();
      q.setPageIndex(Math.max(0, pageIndex)); q.setPageSize(20);
      // 读取父事件已经完成范围授权；日志没有设备列，不能把targetId当设备资产校验。
      return processes.queryPager(q.toNestQuery().and("tenantId", row.getTenantId()).and("applicationId", row.getApplicationId()).getParam());
    });
  }

  @PostMapping("/plans/_query")
  @QueryAction
  public Mono<PagerResult<AlertEntity>> plans(@RequestBody QueryParamEntity q) {
    return queries.page(
      alerts,
      q.clone().toNestQuery().notNull("targetId").getParam(),
      FacilityAssetType.facilityTarget,
      "targetId"
    );
  }

  @PostMapping("/plans/notifications/_query")
  @QueryAction
  public Mono<PagerResult<NotificationEntity>> planNotifications(
    @RequestBody QueryParamEntity q
  ) {
    return queries.page(
      notifications,
      q.clone().toNestQuery().notNull("targetId").getParam(),
      FacilityAssetType.facilityTarget,
      "targetId"
    );
  }

  @PostMapping("/{id}/handle")
  @SaveAction
  public Mono<AlertEntity> handle(
    @PathVariable String id,
    @RequestBody Action input
  ) {
    return manager.action(id, input.state(), input.comment(), input.version());
  }

  @PostMapping("/notifications/_query")
  @QueryAction
  public Mono<PagerResult<NotificationEntity>> notifications(
    @RequestBody QueryParamEntity q
  ) {
    return queries.page(
      notifications,
      q.clone().toNestQuery().notNull("deviceId").getParam(),
      FacilityAssetType.facilityDevice,
      "deviceId"
    );
  }

  @PostMapping("/notifications/{id}/retry")
  @SaveAction
  public Mono<Void> retry(@PathVariable String id) {
    return notifier.retry(id);
  }
}
