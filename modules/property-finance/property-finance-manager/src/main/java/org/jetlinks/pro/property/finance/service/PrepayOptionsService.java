package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 柜台专用的房屋预缴选项；只读可信源，不要求设置或活动维护菜单权限，不返回完整业务快照。 */
@Service
@RequiredArgsConstructor
public class PrepayOptionsService {
    private final FinanceScope scope;
    private final HouseArchiveSource archive;
    private final ActivitySource source;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<ActivityEntity, String> activities;
    private final ReactiveRepository<PrepayConsumptionEntity, String> consumption;

    public Mono<List<Setting>> options(String project, String space, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    if (space == null
                                            || space.isBlank()
                                            || space.length() > 64
                                            || date == null)
                                        return Mono.error(error("prepay_invalid", 400));
                                    return AssetsHolder.assertPermission(
                                                    AssetType.of("space"),
                                                    space,
                                                    CrudAssetPermission.save)
                                            .then(archive.context(project, space, false))
                                            .flatMap(
                                                    context ->
                                                            fromContext(
                                                                    project, space, date, context));
                                }));
    }

    private Mono<List<Setting>> fromContext(
            String project, String space, LocalDate date, PropertyHouseContext context) {
        if (!project.equals(context.house().projectId())
                || !space.equals(context.house().spaceId()))
            return Mono.error(error("prepay_source_changed", 409));
        return availableSettings(project, space).flatMap(rows -> choices(context, rows, date));
    }

    private Mono<List<Setting>> choices(
            PropertyHouseContext context, List<HouseSettingEntity> rows, LocalDate date) {
        if (rows.isEmpty()) return Mono.just(List.of());
        return availableActivities(
                        context.house().projectId(), context.house().spaceId(), rows, date)
                .map(
                        available ->
                                rows.stream()
                                        .map(row -> setting(row, context, available))
                                        .filter(row -> !row.payers().isEmpty())
                                        .toList());
    }

    private Mono<List<HouseSettingEntity>> availableSettings(String project, String space) {
        var query = page(201);
        query.toQuery().orderByAsc("createTime").orderByAsc("id");
        return settings.createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("spaceId", space)
                .and("deleted", false)
                .and("status", "ACTIVE")
                .and("reviewState", "APPROVED")
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.size() > 200)
                                return Mono.error(error("prepay_options_limit", 400));
                            if (rows.isEmpty()) return Mono.just(List.of());
                            var ids =
                                    rows.stream()
                                            .map(HouseSettingEntity::getSubjectId)
                                            .distinct()
                                            .toList();
                            return Mono.zip(
                                            subjects.createQuery()
                                                    .setParam(unpaged())
                                                    .in("id", ids)
                                                    .and("enabled", true)
                                                    .and("deleted", false)
                                                    .fetch()
                                                    .map(SubjectEntity::getId)
                                                    .collectList(),
                                            issued.createQuery()
                                                    .setParam(unpaged())
                                                    .where("projectId", project)
                                                    .in("subjectId", ids)
                                                    .and("deleted", false)
                                                    .fetch()
                                                    .map(ProjectSubjectEntity::getId)
                                                    .collectList())
                                    .map(
                                            active ->
                                                    rows.stream()
                                                            .filter(
                                                                    row ->
                                                                            active.getT1()
                                                                                            .contains(
                                                                                                    row
                                                                                                            .getSubjectId())
                                                                                    && active.getT2()
                                                                                            .contains(
                                                                                                    row
                                                                                                            .getProjectSubjectId()))
                                                            .filter(
                                                                    row ->
                                                                            Set.of(
                                                                                            "MONTHLY",
                                                                                            "QUARTERLY")
                                                                                    .contains(
                                                                                            Objects
                                                                                                    .toString(
                                                                                                            SettingRules
                                                                                                                    .standard(
                                                                                                                            row
                                                                                                                                    .getSnapshotJson())
                                                                                                                    .getSettlementMethod(),
                                                                                                            "")))
                                                            .toList());
                        });
    }

    private Mono<List<ActivityEntity>> availableActivities(
            String project, String space, List<HouseSettingEntity> rows, LocalDate date) {
        var ids = rows.stream().map(HouseSettingEntity::getStandardId).distinct().toList();
        var query = page(501);
        query.toQuery().orderByAsc("startDate").orderByAsc("id");
        return activities
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .in("standardId", ids)
                .and("deleted", false)
                .and("enabled", true)
                .and("startDate", "lte", date.toString())
                .and("endDate", "gte", date.toString())
                .fetch()
                .collectList()
                .flatMap(
                        found -> {
                            if (found.size() > 500)
                                return Mono.error(error("prepay_options_limit", 400));
                            if (found.isEmpty()) return Mono.just(List.of());
                            return Mono.zip(
                                            source.spaces(project),
                                            consumption
                                                    .createQuery()
                                                    .setParam(unpaged())
                                                    .where("projectId", project)
                                                    .and("spaceId", space)
                                                    .in(
                                                            "activityId",
                                                            found.stream()
                                                                    .map(ActivityEntity::getId)
                                                                    .toList())
                                                    .fetch()
                                                    .map(PrepayConsumptionEntity::getActivityId)
                                                    .collectList())
                                    .map(
                                            context ->
                                                    found.stream()
                                                            .filter(
                                                                    activity ->
                                                                            !context.getT2()
                                                                                    .contains(
                                                                                            activity
                                                                                                    .getId()))
                                                            .filter(
                                                                    activity ->
                                                                            PrepayQuoteSource
                                                                                    .covers(
                                                                                            space,
                                                                                            ActivityCodec
                                                                                                    .decode(
                                                                                                            activity
                                                                                                                    .getConfigJson(),
                                                                                                            ActivitySnapshot
                                                                                                                    .class)
                                                                                                    .getInput()
                                                                                                    .getTargetIds(),
                                                                                            context
                                                                                                    .getT1()))
                                                            .toList());
                        });
    }

    private static Setting setting(
            HouseSettingEntity row, PropertyHouseContext context, List<ActivityEntity> available) {
        var selected = SettingRules.customers(row.getSnapshotJson());
        var payers =
                context.customers().stream()
                        .filter(
                                customer ->
                                        selected.stream()
                                                .anyMatch(
                                                        saved ->
                                                                customer.relationshipId()
                                                                                .equals(
                                                                                        saved.get(
                                                                                                "relationshipId"))
                                                                        && customer.customerId()
                                                                                .equals(
                                                                                        saved.get(
                                                                                                "customerId"))))
                        .map(customer -> new Payer(customer.relationshipId(), customer.name()))
                        .toList();
        var standard = SettingRules.standard(row.getSnapshotJson());
        var options =
                available.stream()
                        .filter(
                                activity ->
                                        row.getSubjectId().equals(activity.getSubjectId())
                                                && row.getStandardId()
                                                        .equals(activity.getStandardId()))
                        .map(
                                activity ->
                                        new Activity(
                                                activity.getId(),
                                                activity.getName(),
                                                activity.getEndDate(),
                                                ActivityCodec.decode(
                                                                activity.getConfigJson(),
                                                                ActivitySnapshot.class)
                                                        .getInput()
                                                        .getTiers()))
                        .toList();
        return new Setting(
                row.getId(),
                row.getSubjectName(),
                row.getStandardName(),
                standard.getCompanyId(),
                standard.getSettlementMethod(),
                row.getEffectiveStartDate(),
                row.getEffectiveEndDate(),
                payers,
                options);
    }

    private static QueryParamEntity page(int size) {
        var query = new QueryParamEntity();
        query.setPaging(true);
        query.setPageSize(size);
        return query;
    }

    private static QueryParamEntity unpaged() {
        var query = new QueryParamEntity();
        query.setPaging(false);
        return query;
    }

    public record Payer(String relationshipId, String name) {}

    public record Activity(
            String id, String name, String endDate, List<ActivityInput.Tier> tiers) {}

    public record Setting(
            String id,
            String subjectName,
            String standardName,
            String companyId,
            String settlementMethod,
            String effectiveStartDate,
            String effectiveEndDate,
            List<Payer> payers,
            List<Activity> activities) {}
}
