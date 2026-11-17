package org.jetlinks.pro.property.facility.service;

import java.math.BigDecimal;
import org.hswebframework.web.i18n.LocaleUtils;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;

/** 已发布周期提醒的一次性搬迁；授权范围不变，标记和规则同事务，删除规则后不重建。 */
@Service
@RequiredArgsConstructor
public class FacilityReminderMigration {
  private final CycleStore cycles;
  private final RuleStore rules;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final ReactiveTransactionManager transactions;

  public Mono<Void> project(String project) {
    var q = QueryParamEntity.newQuery().when(project != null, query -> query.is("projectId", project))
      .noPaging().getParam();
    return scope.query(q, FacilityAssetType.facilityCycle, "id")
      .flatMapMany(param -> cycles.createQuery().setParam(param).fetch()).take(20001).collectList()
      .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
      .flatMapMany(Flux::fromIterable).filter(row -> !Boolean.TRUE.equals(row.getRemindersMigrated()))
      .concatMap(row -> migrate(row.getId())).then();
  }

  public Mono<CycleEntity> migrate(String id) {
    return records.read(cycles, id, FacilityAssetType.facilityCycle, CrudAssetPermission.read).flatMap(row -> {
      if (Boolean.TRUE.equals(row.getRemindersMigrated())) return Mono.just(row);
      FacilityCycleRules.validate(row);
      long version = row.getVersion(); row.setRemindersMigrated(true);
      // 搬迁已有配置，不借查询接口接受新配置。规则继承原记录的租户、应用、项目及创建身份。
      return records.update(cycles, row, version).flatMap(saved -> {
        var overdue = rule(saved, "overdue");
        var windows = java.util.stream.Stream.concat(saved.getRule().weekly().values().stream(), saved.getRule().dates().values().stream())
          .flatMap(List::stream).toList();
        Mono<Void> warning = Mono.empty();
        if (windows.stream().anyMatch(w -> w.warningMinutes() != null)) {
          var dueSoon = rule(saved, "dueSoon");
          dueSoon.setLegacySchedule(saved.getRule());
          warning = records.insert(rules, dueSoon, FacilityAssetType.facilityRule).then();
        }
        return records.insert(rules, overdue, FacilityAssetType.facilityRule).then(warning).thenReturn(saved);
      });
    }).as(TransactionalOperator.create(transactions)::transactional);
  }

  private RuleEntity rule(CycleEntity cycle, String type) {
    var row = FacilityScope.initialize(new RuleEntity(), new FacilityContext(
      cycle.getTenantId(), cycle.getApplicationId(), cycle.getCreatorId(), cycle.getCreatorName()));
    FacilityScope.inherit(row, cycle);
    row.setId(FacilityRecords.stableId(cycle.getId(), "task-reminder", type));
    row.setName(cycle.getName() + " · " + LocaleUtils.resolveMessage(
      "message.facility.reminder." + type, "dueSoon".equals(type) ? "到期提醒" : "超期提醒"));
    row.setRuleType(type); row.setTaskKind(cycle.getKind()); row.setCycleId(cycle.getId());
    row.setEnabled(true); row.setThreshold(BigDecimal.ONE); row.setRepeatHours(24); return row;
  }
}
