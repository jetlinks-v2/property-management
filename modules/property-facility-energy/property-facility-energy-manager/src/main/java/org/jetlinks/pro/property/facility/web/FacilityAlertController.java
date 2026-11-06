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
