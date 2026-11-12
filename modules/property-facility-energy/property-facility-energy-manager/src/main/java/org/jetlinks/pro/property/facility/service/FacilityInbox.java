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

  Mono<QueryParamEntity> scoped(QueryParamEntity query) { return scoped(query, true); }

  private Mono<QueryParamEntity> scoped(QueryParamEntity query, boolean personal) {
    return scope.current().flatMap(context -> {
      var base = personal ? query.clone().toNestQuery().and("userId", context.userId()).and("state", "generated").getParam() : query.clone().toNestQuery().getParam();
      // 轮询提醒与派单/审批消息使用不同渠道，但必须经过同一任务资产和本人范围。
      var task = scope.query(base.clone().toNestQuery().in("channel", java.util.List.of("h5-task", "employee")).notNull("taskId").getParam(), FacilityAssetType.facilityTask, "taskId");
      var fault = scope.query(base.clone().toNestQuery().and("channel", "employee").notNull("faultId").getParam(), FacilityAssetType.facilityFault, "faultId");
      var alert = scope.query(base.clone().toNestQuery().and("channel", "employee").notNull("alertId").notNull("deviceId").getParam(), FacilityAssetType.facilityDevice, "deviceId");
      var target = scope.query(base.clone().toNestQuery().and("channel", "employee").notNull("targetId").getParam(), FacilityAssetType.facilityTarget, "targetId");
      return Mono.zip(task, fault, alert, target).map(scopes -> {
        var combined = base.clone(); combined.setTerms(new java.util.ArrayList<>());
        for (var branch : java.util.List.of(scopes.getT1(), scopes.getT2(), scopes.getT3(), scopes.getT4())) {
          var term = new org.hswebframework.ezorm.core.param.Term();
          term.setType(org.hswebframework.ezorm.core.param.Term.Type.or); term.setTerms(branch.getTerms());
          combined.getTerms().add(term);
        }
        return combined;
      });
    });
  }
  public Mono<PagerResult<NotificationEntity>> records(QueryParamEntity query) {
    return scoped(query, false).flatMap(notifications::queryPager);
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
