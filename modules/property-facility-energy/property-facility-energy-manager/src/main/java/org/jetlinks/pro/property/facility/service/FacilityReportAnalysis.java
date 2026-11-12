package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 只聚合授权后的有界期间数据，明细由服务器分页；超限显式失败。 */
@Service
@RequiredArgsConstructor
public class FacilityReportAnalysis {
  private final TaskStore tasks;
  private final FaultStore faults;
  private final AlertEpisodeStore episodes;
  private final GroupStore groups;
  private final FacilityScope scope;
  private final DeviceStore devices;

  static void period(long from, long to) {
    require(from < to && to - from <= 366L * 86400000, "invalid_period");
  }

  private static QueryParamEntity base(String projectId, long from, long to, String time) {
    period(from, to);
    return QueryParamEntity.newQuery().when(projectId != null && !projectId.isBlank(), q -> q.is("projectId", projectId))
      .gte(time, from).lt(time, to).getParam();
  }

  private static QueryParamEntity workQuery(String projectId, long from, long to, String kind) {
    require(Set.of("inspection", "maintenance").contains(kind), "invalid_period");
    return base(projectId, from, to, "scheduledTime").toNestQuery().is("kind", kind).isNull("parentTaskId").getParam();
  }

  private static QueryParamEntity faultQuery(String projectId, long from, long to) {
    period(from, to);
    // 当期验收允许来自上期；两个期间条件必须在同一个权限交集内。
    var query = QueryParamEntity.newQuery().when(projectId != null && !projectId.isBlank(), q -> q.is("projectId", projectId)).getParam();
    var created = new Term(); created.setTerms(QueryParamEntity.newQuery().gte("createTime", from).lt("createTime", to).getParam().getTerms());
    var completed = new Term(); completed.setType(Term.Type.or);
    completed.setTerms(QueryParamEntity.newQuery().gte("completedTime", from).lt("completedTime", to).getParam().getTerms());
    var window = new Term(); window.setTerms(List.of(created, completed)); query.getTerms().add(window);
    return query;
  }

  private <T extends FacilityEntity> Mono<List<T>> rows(GenericReactiveCrudService<T, String> store,
    QueryParamEntity query, AssetType type, String field) {
    query.setPaging(false);
    return scope.query(query, type, field).flatMapMany(q -> store.createQuery().setParam(q).fetch())
      // 不能用前两万条伪造完整聚合；多取一条仅用于检测上限。
      .take(20001).collectList().doOnNext(rows -> require(rows.size() <= 20000, "too_many"));
  }

  public Mono<Map<String, Object>> work(String projectId, long from, long to, String kind) {
    var query = workQuery(projectId, from, to, kind);
    var groupQuery = QueryParamEntity.newQuery().when(projectId != null && !projectId.isBlank(), q -> q.is("projectId", projectId)).getParam();
    return Mono.zip(rows(tasks, query, FacilityAssetType.facilityTask, "id"),
      rows(groups, groupQuery, FacilityAssetType.facilityGroup, "id"))
      .map(data -> FacilityReportMath.work(data.getT1(), data.getT2(), from, to, System.currentTimeMillis()));
  }

  public Mono<Map<String, Object>> faults(String projectId, long from, long to) {
    return rows(faults, faultQuery(projectId, from, to), FacilityAssetType.facilityFault, "id")
      .map(rows -> FacilityReportMath.faults(rows, from, to, System.currentTimeMillis()));
  }

  private Mono<QueryParamEntity> episodeQuery(QueryParamEntity query) {
    return Mono.zip(
      scope.query(query.clone().toNestQuery().notNull("deviceId").getParam(), FacilityAssetType.facilityDevice, "deviceId"),
      scope.query(query.clone().toNestQuery().notNull("targetId").getParam(), FacilityAssetType.facilityTarget, "targetId")
    ).map(scopes -> {
      var device = new Term(); device.setTerms(scopes.getT1().getTerms());
      var target = new Term(); target.setType(Term.Type.or); target.setTerms(scopes.getT2().getTerms());
      var result = query.clone(); result.setTerms(List.of(device, target));
      result.setPageIndex(Math.max(0, result.getPageIndex()));
      result.setPageSize(Math.min(200, Math.max(1, result.getPageSize())));
      return result;
    });
  }

  public Mono<Map<String, Object>> alerts(String projectId, long from, long to) {
    var query = base(projectId, from, to, "firstTime"); query.setPaging(false);
    return episodeQuery(query).flatMapMany(q -> episodes.createQuery().setParam(q).fetch())
      .take(20001).collectList().doOnNext(rows -> require(rows.size() <= 20000, "too_many"))
      .flatMap(events -> {
        var ids = events.stream().map(AlertEntity::getDeviceId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Mono.just(FacilityReportMath.alerts(events, from, to, System.currentTimeMillis()));
        return rows(devices, QueryParamEntity.newQuery().in("id", ids).getParam(), FacilityAssetType.facilityDevice, "id")
          .map(visible -> {
            var names = new HashMap<String, String>(); visible.forEach(device -> names.put(device.getId(), device.getName()));
            var result = FacilityReportMath.alerts(events, from, to, System.currentTimeMillis());
            @SuppressWarnings("unchecked") var ranking = (List<Map<String, Object>>) result.get("devices");
            ranking.forEach(device -> device.put("name", names.getOrDefault(device.get("id"), "—")));
            return result;
          });
      });
  }

  public Mono<PagerResult<TaskEntity>> workDetails(String projectId, long from, long to, String kind, String groupId, String bucket, QueryParamEntity input) {
    var query = mandatory(input, workQuery(projectId, from, to, kind));
    if (groupId != null || bucket != null) {
      require(bucket == null || Set.of("onTime", "late", "dueIncomplete", "notDue", "closed", "cancelled").contains(bucket), "invalid_period");
      long now = System.currentTimeMillis();
      return rows(tasks, query.clone(), FacilityAssetType.facilityTask, "id").flatMap(source -> {
        var ids = source.stream().filter(task -> groupId == null || task.getGroupIds() != null && task.getGroupIds().contains(groupId))
          .filter(task -> bucket == null || bucket.equals(FacilityReportMath.workBucket(task, now))).map(TaskEntity::getId).toList();
        return ids.isEmpty() ? Mono.just(PagerResult.of(0, List.<TaskEntity>of(), query)) :
          scope.query(query.toNestQuery().in("id", ids).getParam(), FacilityAssetType.facilityTask, "id").flatMap(tasks::queryPager);
      });
    }
    return scope.query(query, FacilityAssetType.facilityTask, "id").flatMap(tasks::queryPager);
  }

  public Mono<PagerResult<FaultEntity>> faultDetails(String projectId, long from, long to, QueryParamEntity input) {
    var query = mandatory(input, faultQuery(projectId, from, to)).toNestQuery()
      .not("state", "cancelled").getParam();
    return scope.query(query, FacilityAssetType.facilityFault, "id").flatMap(faults::queryPager);
  }

  public Mono<PagerResult<AlertEpisodeEntity>> alertDetails(String projectId, long from, long to, QueryParamEntity input) {
    var query = mandatory(input, base(projectId, from, to, "firstTime"));
    return episodeQuery(query).flatMap(episodes::queryPager);
  }

  private static QueryParamEntity mandatory(QueryParamEntity input, QueryParamEntity required) {
    var request = input.clone();
    var client = new Term(); client.setTerms(request.getTerms());
    var filter = new Term(); filter.setTerms(required.getTerms());
    request.setTerms(List.of(client, filter));
    request.setPaging(true);
    return request;
  }
}
