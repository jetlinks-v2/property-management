package org.jetlinks.pro.property.facility.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.NotificationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 本人消息查询仍与关联任务资产权限相交，不开放通用通知记录查询。 */
@Service
@RequiredArgsConstructor
public class FacilityInbox {
  private final NotificationStore notifications;
  private final FacilityScope scope;

  Mono<QueryParamEntity> scoped(QueryParamEntity query) {
    return scope.current().flatMap(context -> scope.query(query.clone().toNestQuery()
      .and("userId", context.userId()).and("channel", "h5-task").and("state", "generated").getParam(),
      FacilityAssetType.facilityTask, "taskId"));
  }
  public Mono<PagerResult<NotificationEntity>> page(QueryParamEntity query) {
    return scoped(query).flatMap(notifications::queryPager);
  }
  public Mono<Integer> unread(String projectId) {
    var query = QueryParamEntity.newQuery().isNull("readTime");
    if (projectId != null && !projectId.isBlank()) query.and("projectId", projectId);
    return scoped(query.getParam()).flatMap(param -> notifications.createQuery().setParam(param).count());
  }
  public Mono<Void> read(String id, String projectId) {
    var query = QueryParamEntity.newQuery().isNull("readTime");
    if (id != null) query.and("id", id);
    if (projectId != null && !projectId.isBlank()) query.and("projectId", projectId);
    return scoped(query.getParam()).flatMap(param -> notifications.createUpdate()
      .set("readTime", System.currentTimeMillis()).accept(param).execute()).then();
  }
}
