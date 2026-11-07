package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.activity.ActivityCodec.*;
import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 项目活动按钮审批，所有变更与审计同事务；不开放物理删除和客户端审批字段。 */
@Service
@RequiredArgsConstructor
public class ActivityService {
    private final FinanceScope scope;
    private final ActivitySource source;
    private final SubjectService subjects;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<ActivityEntity, String> activities;
    private final ReactiveRepository<ActivityLogEntity, String> logs;
    private final ReactiveRepository<SubjectEntity, String> subjectRows;
    private final ReactiveRepository<PrepayConsumptionEntity, String> consumption;

    public Mono<Map<String, Object>> options(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .then(Mono.zip(source.spaces(project), source.types(), standardOptions(project)))
                .map(
                        values ->
                                Map.of(
                                        "spaces",
                                        values.getT1(),
                                        "types",
                                        values.getT2(),
                                        "standards",
                                        values.getT3()));
    }

    private Mono<List<StandardOption>> standardOptions(String project) {
        return issued.createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .fetch()
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(List.of())
                                        : subjectRows
                                                .createQuery()
                                                .in("id", ids)
                                                .and("deleted", false)
                                                .and("enabled", true)
                                                .fetch()
                                                .map(SubjectEntity::getId)
                                                .collectList())
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(List.of())
                                        : standards
                                                .createQuery()
                                                .where("projectId", project)
                                                .and("deleted", false)
                                                .in("subjectId", ids)
                                                .fetch()
                                                .map(
                                                        row ->
                                                                new StandardOption(
                                                                        row.getId(),
                                                                        row.getSubjectId(),
                                                                        row.getSubjectName(),
                                                                        row.getStandardName(),
                                                                        row.getSettlementMethod()))
                                                .collectList());
    }

    public Mono<PagerResult<Summary>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("deleted", false));
                                    return QueryHelper.transformPageResult(
                                            QueryHelper.queryPager(q, activities::createQuery),
                                            rows ->
                                                    Mono.just(
                                                            rows.stream()
                                                                    .map(ActivityService::summary)
                                                                    .toList()));
                                }));
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, false))
                .flatMap(
                        row ->
                                permissions(config(row), CrudAssetPermission.read)
                                        .thenReturn(view(row)));
    }

    public Mono<PagerResult<ActivityLogEntity>> history(
            String project, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, true))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("activityId", id));
                                    return QueryHelper.transformPageResult(
                                            QueryHelper.queryPager(q, logs::createQuery),
                                            rows ->
                                                    Flux.fromIterable(rows)
                                                            .concatMap(
                                                                    log ->
                                                                            logPermission(
                                                                                            log
                                                                                                    .getBeforeJson())
                                                                                    .then(
                                                                                            logPermission(
                                                                                                    log
                                                                                                            .getAfterJson()))
                                                                                    .thenReturn(
                                                                                            log))
                                                            .collectList());
                                }));
    }

    @Transactional
    public Mono<Detail> save(String project, String id, ActivityInput submitted) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var input =
                                                    decode(encode(submitted), ActivityInput.class);
                                            ActivityRules.validate(input, null);
                                            // 与标准维护遵循科目→下发→标准→活动的锁序，不能先锁活动再反向锁标准。
                                            return subjects.subject(input.getSubjectId(), true)
                                                    .flatMap(
                                                            subject -> {
                                                                if (!Boolean.TRUE.equals(
                                                                        subject.getEnabled()))
                                                                    return Mono.error(
                                                                            error(
                                                                                    "activity_standard",
                                                                                    409));
                                                                var q = new QueryParamEntity();
                                                                q.setForUpdate(true);
                                                                var standardQuery =
                                                                        new QueryParamEntity();
                                                                standardQuery.setForUpdate(true);
                                                                return issued.createQuery()
                                                                        .setParam(q)
                                                                        .where("projectId", project)
                                                                        .and(
                                                                                "subjectId",
                                                                                input
                                                                                        .getSubjectId())
                                                                        .and("deleted", false)
                                                                        .fetchOne()
                                                                        .switchIfEmpty(
                                                                                Mono.error(
                                                                                        error(
                                                                                                "activity_standard",
                                                                                                409)))
                                                                        .then(
                                                                                standards
                                                                                        .createQuery()
                                                                                        .setParam(
                                                                                                standardQuery)
                                                                                        .where(
                                                                                                "projectId",
                                                                                                project)
                                                                                        .and(
                                                                                                "id",
                                                                                                input
                                                                                                        .getStandardId())
                                                                                        .and(
                                                                                                "subjectId",
                                                                                                input
                                                                                                        .getSubjectId())
                                                                                        .and(
                                                                                                "deleted",
                                                                                                false)
                                                                                        .fetchOne()
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .error(
                                                                                                                error(
                                                                                                                        "activity_standard",
                                                                                                                        409))))
                                                                        .flatMap(
                                                                                standard ->
                                                                                        snapshot(
                                                                                                project,
                                                                                                input,
                                                                                                subject,
                                                                                                standard));
                                                            })
                                                    .flatMap(
                                                            snapshot ->
                                                                    id == null
                                                                            ? create(
                                                                                    project,
                                                                                    snapshot, auth)
                                                                            : row(
                                                                                            project,
                                                                                            id,
                                                                                            true,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            old ->
                                                                                                    permissions(
                                                                                                                    config(
                                                                                                                            old),
                                                                                                                    CrudAssetPermission
                                                                                                                            .save)
                                                                                                            .then(
                                                                                                                    replace(
                                                                                                                            old,
                                                                                                                            snapshot,
                                                                                                                            input
                                                                                                                                    .getVersion(),
                                                                                                                            "UPDATE",
                                                                                                                            auth))));
                                        }))
                .as(MonoTracer.create("/property/finance/activity/save"));
    }

    private Mono<ActivitySnapshot> snapshot(
            String project, ActivityInput input, SubjectEntity subject, StandardEntity standard) {
        ActivityRules.validate(input, standard.getSettlementMethod());
        return AssetsHolder.currentReactive()
                .flatMap(
                        holder ->
                                holder.assertPermission(
                                        "space", input.getTargetIds(), CrudAssetPermission.save))
                .then(Mono.zip(source.spaces(project), source.types()))
                .map(
                        values -> {
                            var byId =
                                    new HashMap<
                                            String,
                                            org.jetlinks.pro.property.base.archive
                                                    .PropertySpaceInfo>();
                            values.getT1().forEach(node -> byId.put(node.id(), node));
                            if (!byId.keySet().containsAll(input.getTargetIds()))
                                throw error("activity_targets", 400);
                            var type =
                                    values.getT2().stream()
                                            .filter(
                                                    option ->
                                                            option.value()
                                                                    .equals(
                                                                            input
                                                                                    .getActivityType()))
                                            .findFirst()
                                            .orElseThrow(() -> error("activity_type", 400));
                            var snapshot = new ActivitySnapshot();
                            snapshot.setInput(input);
                            snapshot.setActivityTypeName(type.text());
                            snapshot.setSubjectName(subject.getName());
                            snapshot.setStandardName(standard.getStandardName());
                            snapshot.setTargets(
                                    input.getTargetIds().stream().map(byId::get).toList());
                            return snapshot;
                        });
    }

    private Mono<Detail> create(String project, ActivitySnapshot snapshot, Authentication auth) {
        var row = new ActivityEntity();
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        row.setProjectId(project);
        row.setEnabled(false);
        row.setReviewState("PENDING");
        apply(row, snapshot);
        return activities.insert(row).then(log(null, row, "CREATE", auth)).thenReturn(view(row));
    }

    private Mono<Detail> replace(
            ActivityEntity old,
            ActivitySnapshot snapshot,
            Long version,
            String operation,
            Authentication auth) {
        return replace(old, snapshot, version, operation, auth, old.getEnabled());
    }

    private Mono<Detail> replace(
            ActivityEntity old,
            ActivitySnapshot snapshot,
            Long version,
            String operation,
            Authentication auth,
            boolean enabled) {
        version(old, version);
        var next = new ActivityEntity();
        BeanUtils.copyProperties(old, next);
        apply(next, snapshot);
        next.setEnabled(enabled);
        // 编辑保存使旧审批失效；新配置只有重新通过审批后才可参与柜台优惠。
        if ("UPDATE".equals(operation)) {
            next.setReviewState("PENDING");
            next.setEnabled(false);
            next.setReviewedBy(null);
            next.setReviewedName(null);
            next.setReviewedAt(null);
            next.setReviewReason(null);
        }

        next.setVersion(old.getVersion() + 1);
        next.setModifyTime(System.currentTimeMillis());
        next.setModifierId(auth.getUser().getId());
        next.setModifierName(FinanceScope.operatorName(auth));
        return permissions(config(old), CrudAssetPermission.save)
                .then(
                        "UPDATE".equals(operation)
                                        && !protectedConfig(config(old).getInput())
                                                .equals(protectedConfig(snapshot.getInput()))
                                ? assertNotConsumed(old)
                                : Mono.empty())
                .then(
                        audit(activities.createUpdate(), auth, old.getVersion())
                                .set("name", next.getName())
                                .set("subjectId", next.getSubjectId())
                                .set("standardId", next.getStandardId())
                                .set("startDate", next.getStartDate())
                                .set("endDate", next.getEndDate())
                                .set("configJson", next.getConfigJson())
                                .set("enabled", next.getEnabled())
                                .set("reviewState", next.getReviewState())
                                .set("reviewedBy", next.getReviewedBy())
                                .set("reviewedName", next.getReviewedName())
                                .set("reviewedAt", next.getReviewedAt())
                                .set("reviewReason", next.getReviewReason())
                                .set("deleted", next.getDeleted())
                                .where("id", old.getId())
                                .and("projectId", old.getProjectId())
                                .and("version", old.getVersion())
                                .and("deleted", false)
                                .execute()
                                .flatMap(SubjectService::changed)
                                .then(log(old, next, operation, auth))
                                .thenReturn(view(next)));
    }

    public record ReviewItem(String id, Long version) {}

    public record ReviewInput(List<ReviewItem> items, String reason) {}

    /** 固定 ID 顺序锁定全批并预检，任一过期/无权/已审批则整批回滚，不产生部分审批。 */
    @Transactional
    public Mono<List<Detail>> review(String project, ReviewInput input, boolean approve) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (input == null
                                                    || input.items() == null
                                                    || input.items().isEmpty()
                                                    || input.items().size() > 100
                                                    || input.reason() == null
                                                    || input.reason().isBlank()
                                                    || input.reason().length() > 1000)
                                                return Mono.error(
                                                        error("activity_review_invalid", 400));
                                            var ids = new HashSet<String>();
                                            for (var item : input.items()) {
                                                if (item == null
                                                        || item.id() == null
                                                        || item.id().isBlank()
                                                        || item.id().length() > 64
                                                        || item.version() == null
                                                        || !ids.add(item.id()))
                                                    return Mono.error(
                                                            error("activity_review_invalid", 400));
                                            }
                                            return Flux.fromIterable(
                                                            input.items().stream()
                                                                    .sorted(
                                                                            Comparator.comparing(
                                                                                    ReviewItem::id))
                                                                    .toList())
                                                    .concatMap(
                                                            item ->
                                                                    row(
                                                                                    project,
                                                                                    item.id(), true,
                                                                                    false)
                                                                            .flatMap(
                                                                                    row -> {
                                                                                        version(
                                                                                                row,
                                                                                                item
                                                                                                        .version());
                                                                                        if (!"PENDING"
                                                                                                .equals(
                                                                                                        row
                                                                                                                .getReviewState()))
                                                                                            return Mono
                                                                                                    .error(
                                                                                                            error(
                                                                                                                    "activity_review_state",
                                                                                                                    409));
                                                                                        return permissions(
                                                                                                        config(
                                                                                                                row),
                                                                                                        CrudAssetPermission
                                                                                                                .save)
                                                                                                .thenReturn(
                                                                                                        row);
                                                                                    }))
                                                    .collectList()
                                                    .flatMapMany(
                                                            rows ->
                                                                    Flux.fromIterable(rows)
                                                                            .concatMap(
                                                                                    old -> {
                                                                                        var next =
                                                                                                new ActivityEntity();
                                                                                        BeanUtils
                                                                                                .copyProperties(
                                                                                                        old,
                                                                                                        next);
                                                                                        next
                                                                                                .setReviewState(
                                                                                                        approve
                                                                                                                ? "APPROVED"
                                                                                                                : "REJECTED");
                                                                                        next
                                                                                                .setEnabled(
                                                                                                        approve);
                                                                                        next
                                                                                                .setReviewedBy(
                                                                                                        auth.getUser()
                                                                                                                .getId());
                                                                                        next
                                                                                                .setReviewedName(
                                                                                                        FinanceScope
                                                                                                                .operatorName(
                                                                                                                        auth));
                                                                                        next
                                                                                                .setReviewedAt(
                                                                                                        System
                                                                                                                .currentTimeMillis());
                                                                                        next
                                                                                                .setReviewReason(
                                                                                                        input.reason()
                                                                                                                .trim());
                                                                                        next
                                                                                                .setVersion(
                                                                                                        old
                                                                                                                        .getVersion()
                                                                                                                + 1);
                                                                                        next
                                                                                                .setModifyTime(
                                                                                                        next
                                                                                                                .getReviewedAt());
                                                                                        next
                                                                                                .setModifierId(
                                                                                                        auth.getUser()
                                                                                                                .getId());
                                                                                        next
                                                                                                .setModifierName(
                                                                                                        FinanceScope
                                                                                                                .operatorName(
                                                                                                                        auth));
                                                                                        return audit(
                                                                                                        activities
                                                                                                                .createUpdate(),
                                                                                                        auth,
                                                                                                        old
                                                                                                                .getVersion())
                                                                                                .set(
                                                                                                        "reviewState",
                                                                                                        next
                                                                                                                .getReviewState())
                                                                                                .set(
                                                                                                        "enabled",
                                                                                                        next
                                                                                                                .getEnabled())
                                                                                                .set(
                                                                                                        "reviewedBy",
                                                                                                        next
                                                                                                                .getReviewedBy())
                                                                                                .set(
                                                                                                        "reviewedName",
                                                                                                        next
                                                                                                                .getReviewedName())
                                                                                                .set(
                                                                                                        "reviewedAt",
                                                                                                        next
                                                                                                                .getReviewedAt())
                                                                                                .set(
                                                                                                        "reviewReason",
                                                                                                        next
                                                                                                                .getReviewReason())
                                                                                                .where(
                                                                                                        "id",
                                                                                                        old
                                                                                                                .getId())
                                                                                                .and(
                                                                                                        "projectId",
                                                                                                        project)
                                                                                                .and(
                                                                                                        "version",
                                                                                                        old
                                                                                                                .getVersion())
                                                                                                .and(
                                                                                                        "deleted",
                                                                                                        false)
                                                                                                .execute()
                                                                                                .flatMap(
                                                                                                        SubjectService
                                                                                                                ::changed)
                                                                                                .then(
                                                                                                        log(
                                                                                                                old,
                                                                                                                next,
                                                                                                                approve
                                                                                                                        ? "APPROVE"
                                                                                                                        : "REJECT",
                                                                                                                auth))
                                                                                                .thenReturn(
                                                                                                        view(
                                                                                                                next));
                                                                                    }))
                                                    .collectList();
                                        }))
                .as(MonoTracer.create("/property/finance/activity/review"));
    }

    @Transactional
    public Mono<Detail> state(String project, String id, Long version, boolean enabled) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, true, false)
                                        .flatMap(
                                                old -> {
                                                    if (enabled
                                                            && !"APPROVED"
                                                                    .equals(old.getReviewState()))
                                                        return Mono.error(
                                                                error(
                                                                        "activity_review_required",
                                                                        409));
                                                    return replace(
                                                            old,
                                                            config(old),
                                                            version,
                                                            enabled ? "ENABLE" : "DISABLE",
                                                            auth,
                                                            enabled);
                                                }));
    }

    @Transactional
    public Mono<Detail> changeEnd(
            String project, String id, Long version, String date, boolean extend) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, true, false)
                                        .flatMap(
                                                old -> {
                                                    ActivityRules.changeEnd(
                                                            old.getStartDate(),
                                                            old.getEndDate(),
                                                            date,
                                                            extend,
                                                            LocalDate.now());
                                                    var snapshot = config(old);
                                                    snapshot.getInput().setEndDate(date);
                                                    return replace(
                                                            old,
                                                            snapshot,
                                                            version,
                                                            extend ? "EXTEND" : "END",
                                                            auth);
                                                }));
    }

    @Transactional
    public Mono<Void> delete(String project, String id, Long version) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, true, false)
                                        .flatMap(
                                                old -> {
                                                    version(old, version);
                                                    var next = new ActivityEntity();
                                                    BeanUtils.copyProperties(old, next);
                                                    next.setDeleted(true);
                                                    next.setEnabled(false);
                                                    next.setVersion(version + 1);
                                                    next.setDeletedTime(System.currentTimeMillis());
                                                    next.setModifyTime(next.getDeletedTime());
                                                    next.setModifierId(auth.getUser().getId());
                                                    next.setModifierName(
                                                            FinanceScope.operatorName(auth));
                                                    return permissions(
                                                                    config(old),
                                                                    CrudAssetPermission.save)
                                                            .then(assertNotConsumed(old))
                                                            .then(
                                                                    audit(
                                                                                    activities
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .set("enabled", false)
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    next
                                                                                            .getDeletedTime())
                                                                            .where(
                                                                                    "projectId",
                                                                                    project)
                                                                            .and("id", id)
                                                                            .and("version", version)
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    SubjectService
                                                                                            ::changed)
                                                                            .then(
                                                                                    log(
                                                                                            old,
                                                                                            next,
                                                                                            "DELETE",
                                                                                            auth)));
                                                }));
    }

    /** 消费快照不能被配置编辑或删除抹掉；启停、延期/提前结束仅影响后续报名。 */
    private Mono<Void> assertNotConsumed(ActivityEntity activity) {
        return consumption
                .createQuery()
                .where("projectId", activity.getProjectId())
                .and("activityId", activity.getId())
                .count()
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("activity_consumed", 409))
                                        : Mono.empty());
    }

    private static String protectedConfig(ActivityInput input) {
        return encode(
                Arrays.asList(
                        input.getActivityType(),
                        input.getSubjectId(),
                        input.getStandardId(),
                        input.getStartDate(),
                        input.getEndDate(),
                        input.getOffsetArrears(),
                        input.getOffsetStartDate(),
                        input.getOffsetEndDate(),
                        input.getTargetIds().stream().sorted().toList(),
                        input.getTiers().stream()
                                .sorted(
                                        Comparator.comparing(ActivityInput.Tier::paidMonths)
                                                .thenComparing(ActivityInput.Tier::giftMonths))
                                .toList()));
    }

    private Mono<ActivityEntity> row(
            String project, String id, boolean lock, boolean includeDeleted) {
        var q = new QueryParamEntity();
        q.setForUpdate(lock);
        var query = activities.createQuery().setParam(q).where("projectId", project).and("id", id);
        if (!includeDeleted) query.and("deleted", false);
        return query.fetchOne().switchIfEmpty(Mono.error(error("activity_not_found", 404)));
    }

    private Mono<Void> log(
            ActivityEntity before, ActivityEntity after, String operation, Authentication auth) {
        var log = new ActivityLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(after.getProjectId());
        log.setActivityId(after.getId());
        log.setOperation(operation);
        if (before != null) log.setBeforeJson(encode(view(before)));
        log.setAfterJson(encode(view(after)));
        log.setCreateTime(System.currentTimeMillis());
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(FinanceScope.operatorName(auth));
        return logs.insert(log).then();
    }

    private static void apply(ActivityEntity row, ActivitySnapshot snapshot) {
        var input = snapshot.getInput();
        row.setName(input.getName());
        row.setSubjectId(input.getSubjectId());
        row.setStandardId(input.getStandardId());
        row.setStartDate(input.getStartDate());
        row.setEndDate(input.getEndDate());
        input.setVersion(row.getVersion());
        row.setConfigJson(encode(snapshot));
    }

    private static void version(ActivityEntity row, Long version) {
        if (version == null || !version.equals(row.getVersion()))
            throw error("version_conflict", 409);
    }

    private static ActivitySnapshot config(ActivityEntity row) {
        var snapshot = decode(row.getConfigJson(), ActivitySnapshot.class);
        snapshot.getInput().setVersion(row.getVersion());
        return snapshot;
    }

    private static Detail view(ActivityEntity row) {
        return new Detail(
                row,
                config(row),
                ActivityRules.status(row.getStartDate(), row.getEndDate(), LocalDate.now()));
    }

    private static Summary summary(ActivityEntity row) {
        var snapshot = config(row);
        return new Summary(
                row,
                snapshot.getActivityTypeName(),
                snapshot.getSubjectName(),
                snapshot.getStandardName(),
                ActivityRules.status(row.getStartDate(), row.getEndDate(), LocalDate.now()));
    }

    private static Mono<Void> permissions(
            ActivitySnapshot snapshot, CrudAssetPermission permission) {
        return AssetsHolder.currentReactive()
                .flatMap(
                        holder ->
                                holder.assertPermission(
                                        "space", snapshot.getInput().getTargetIds(), permission));
    }

    private static Mono<Void> logPermission(String json) {
        if (json == null) return Mono.empty();
        var snapshot = decode(json, Detail.class).config();
        return permissions(snapshot, CrudAssetPermission.read);
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var q = SubjectService.page(input);
        q.setForUpdate(false);
        q.setPaging(true);
        if (q.getSorts().isEmpty()) q.toQuery().orderByDesc("createTime");
        return q;
    }

    public record Detail(ActivityEntity activity, ActivitySnapshot config, String runtimeStatus) {}

    /** 列表不返回优惠空间路径；完整详情及历史快照另校验全部目标空间权限。 */
    public record Summary(
            ActivityEntity activity,
            String activityTypeName,
            String subjectName,
            String standardName,
            String runtimeStatus) {}

    public record StandardOption(
            String id,
            String subjectId,
            String subjectName,
            String standardName,
            String settlementMethod) {}
}
