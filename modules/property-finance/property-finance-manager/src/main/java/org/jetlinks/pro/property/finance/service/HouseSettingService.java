package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.*;
import org.jetlinks.pro.property.finance.standard.StandardView;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 多收费对象共享同一应收，不分摊；保存快照与即时出账在同一个响应式事务内完成。 */
@Service
@RequiredArgsConstructor
public class HouseSettingService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HouseArchiveSource archive;
    private final SubjectService subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReceivableGenerationService generation;

    public Mono<PagerResult<SettingView>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.toNestQuery(
                                            nested ->
                                                    nested.and("projectId", project)
                                                            .and("deleted", false));
                                    // 项目和房屋是两级授权；不把“项目可见”当作该项目所有房屋均可见。
                                    return AssetsHolder.injectQueryParam(query, SPACE, "spaceId")
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.queryPager(
                                                                    secured, settings::createQuery))
                                            .flatMap(page -> withBilledFlags(page, query));
                                }));
    }

    private Mono<PagerResult<SettingView>> withBilledFlags(
            PagerResult<HouseSettingEntity> page, QueryParamEntity query) {
        if (page.getData().isEmpty())
            return Mono.just(PagerResult.of(page.getTotal(), List.of(), query));
        var ids = page.getData().stream().map(HouseSettingEntity::getId).toList();
        return bills.createQuery()
                .in("settingId", ids)
                .select("settingId")
                .fetch()
                .map(ReceivableEntity::getSettingId)
                .collect(java.util.stream.Collectors.toSet())
                .map(
                        used ->
                                PagerResult.of(
                                        page.getTotal(),
                                        page.getData().stream()
                                                .map(
                                                        row ->
                                                                SettingView.of(
                                                                        row,
                                                                        used.contains(row.getId())))
                                                .toList(),
                                        query));
    }

    public Mono<SettingView> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(setting(project, id, false))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                SPACE, row.getSpaceId(), CrudAssetPermission.read)
                                        .then(view(row)));
    }

    public Mono<PropertyHousePage> houses(
            String project, String space, String keyword, int pageIndex, int pageSize) {
        return scope.project(project, CrudAssetPermission.read)
                .then(archive.houses(project, space, keyword, pageIndex, pageSize));
    }

    public Mono<PropertyHouseContext> houseContext(String project, String space) {
        return scope.project(project, CrudAssetPermission.read)
                .then(archive.context(project, space, false));
    }

    /** 收费设置菜单自身的选项契约，用户无需取得收费标准维护权限。 */
    public Mono<Map<String, Object>> options(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        issued.createQuery()
                                .where("projectId", project)
                                .and("deleted", false)
                                .fetch()
                                .collectList()
                                .flatMap(
                                        relations ->
                                                standards
                                                        .createQuery()
                                                        .where("projectId", project)
                                                        .and("deleted", false)
                                                        .fetch()
                                                        .map(
                                                                row ->
                                                                        StandardView.of(
                                                                                row,
                                                                                LocalDate.now()))
                                                        .collectList()
                                                        .map(
                                                                available ->
                                                                        Map.<String, Object>of(
                                                                                "projectSubjects",
                                                                                relations,
                                                                                "standards",
                                                                                available))));
    }

    @Transactional
    public Mono<SaveResult> create(String project, SettingInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, date))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                prepare(project, input)
                                        .flatMap(
                                                next ->
                                                        assertNoOverlap(next, null)
                                                                .then(insert(next, auth)))
                                        .flatMap(row -> generated(row, date, auth)))
                .as(MonoTracer.create("/property/finance/setting/create"));
    }

    private Mono<HouseSettingEntity> prepare(String project, SettingInput input) {
        return Mono.defer(
                () -> {
                    SettingRules.validate(input);
                    // 外层已持有期间锁；新配置不得在开放日回填已结期间的费用。
                    return periodGate
                            .assertOpen(project, LocalDate.parse(input.getEffectiveStartDate()))
                            .then(
                                    AssetsHolder.assertPermission(
                                            SPACE, input.getSpaceId(), CrudAssetPermission.save))
                            .then(archive.context(project, input.getSpaceId(), true))
                            .flatMap(
                                    context ->
                                            standard(project, input.getStandardId(), false)
                                                    .flatMap(
                                                            standard ->
                                                                    subjects.subject(
                                                                                    standard
                                                                                            .getSubjectId(),
                                                                                    true)
                                                                            .filter(
                                                                                    subject ->
                                                                                            Boolean
                                                                                                    .TRUE
                                                                                                    .equals(
                                                                                                            subject
                                                                                                                    .getEnabled()))
                                                                            .switchIfEmpty(
                                                                                    Mono.error(
                                                                                            error(
                                                                                                    "setting_subject_unavailable",
                                                                                                    409)))
                                                                            .then(
                                                                                    projectSubject(
                                                                                            project,
                                                                                            input
                                                                                                    .getProjectSubjectId(),
                                                                                            standard
                                                                                                    .getSubjectId()))
                                                                            .then(
                                                                                    standard(
                                                                                            project,
                                                                                            input
                                                                                                    .getStandardId(),
                                                                                            true))
                                                                            .flatMap(
                                                                                    locked ->
                                                                                            Objects
                                                                                                            .equals(
                                                                                                                    locked
                                                                                                                            .getSubjectId(),
                                                                                                                    standard
                                                                                                                            .getSubjectId())
                                                                                                    ? Mono
                                                                                                            .just(
                                                                                                                    locked)
                                                                                                    : Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "version_conflict",
                                                                                                                            409))))
                                                    .map(
                                                            standard ->
                                                                    SettingRules.snapshot(
                                                                            project, input,
                                                                            standard, context)));
                });
    }

    private Mono<ProjectSubjectEntity> projectSubject(String project, String id, String subjectId) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return issued.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("subjectId", subjectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_subject_unavailable", 409)));
    }

    private Mono<StandardEntity> standard(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return standards
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("standard_not_found", 404)));
    }

    private Mono<Void> assertNoOverlap(HouseSettingEntity candidate, String excluded) {
        return settings.createQuery()
                .where("projectId", candidate.getProjectId())
                .and("spaceId", candidate.getSpaceId())
                .and("subjectId", candidate.getSubjectId())
                .and("deleted", false)
                .fetch()
                .filter(row -> !Objects.equals(row.getId(), excluded))
                .any(
                        row ->
                                SettingRules.overlaps(
                                        candidate.getEffectiveStartDate(),
                                        candidate.getEffectiveEndDate(),
                                        row.getEffectiveStartDate(),
                                        row.getEffectiveEndDate()))
                .flatMap(
                        overlap ->
                                overlap ? Mono.error(error("setting_overlap", 409)) : Mono.empty());
    }

    private Mono<HouseSettingEntity> insert(HouseSettingEntity next, Authentication auth) {
        return settings.createQuery()
                .where("projectId", next.getProjectId())
                .and("spaceId", next.getSpaceId())
                .and("subjectId", next.getSubjectId())
                .fetch()
                .map(HouseSettingEntity::getConfigVersion)
                .reduce(0, Math::max)
                .flatMap(
                        version -> {
                            next.setId(UUID.randomUUID().toString());
                            next.setConfigVersion(version + 1);
                            init(next, auth);
                            return org.jetlinks.pro.tenant.holder.TenantHolder.currentId()
                                    .defaultIfEmpty("")
                                    .flatMap(
                                            tenant -> {
                                                next.setTenantId(tenant);
                                                return settings.insert(next).thenReturn(next);
                                            });
                        });
    }

    private Mono<SaveResult> generated(
            HouseSettingEntity row, LocalDate date, Authentication auth) {
        return generation
                .generate(row, date, auth)
                .flatMap(
                        result ->
                                view(row)
                                        .map(
                                                view ->
                                                        new SaveResult(
                                                                view,
                                                                result.generated(),
                                                                result.retained())));
    }

    private Mono<SettingView> view(HouseSettingEntity row) {
        return hasBills(row.getId()).map(billed -> SettingView.of(row, billed));
    }

    private Mono<Boolean> hasBills(String id) {
        // 删除/拆分过的账单也算已出账，不能通过“删账后编辑设置”重写历史。
        return bills.createQuery().where("settingId", id).fetchOne().hasElement();
    }

    @Transactional
    public Mono<SaveResult> update(String project, String id, SettingInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, date))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                prepare(project, input)
                                        .flatMap(
                                                next ->
                                                        setting(project, id, true)
                                                                .flatMap(
                                                                        old -> {
                                                                            requireVersion(
                                                                                    old,
                                                                                    input
                                                                                            .getVersion());
                                                                            if (!old.getSpaceId()
                                                                                            .equals(
                                                                                                    next
                                                                                                            .getSpaceId())
                                                                                    || !old.getProjectSubjectId()
                                                                                            .equals(
                                                                                                    next
                                                                                                            .getProjectSubjectId()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "setting_scope_immutable",
                                                                                                400));
                                                                            return requireUnbilled(
                                                                                            old)
                                                                                    .then(
                                                                                            assertNoOverlap(
                                                                                                    next,
                                                                                                    id))
                                                                                    .then(
                                                                                            replace(
                                                                                                    old,
                                                                                                    next,
                                                                                                    auth));
                                                                        }))
                                        .flatMap(row -> generated(row, date, auth)))
                .as(MonoTracer.create("/property/finance/setting/update"));
    }

    private Mono<Void> requireUnbilled(HouseSettingEntity row) {
        return hasBills(row.getId())
                .flatMap(
                        used ->
                                used
                                        ? Mono.error(error("setting_already_billed", 409))
                                        : Mono.empty());
    }

    private Mono<HouseSettingEntity> replace(
            HouseSettingEntity old, HouseSettingEntity next, Authentication auth) {
        var update = audit(settings.createUpdate(), auth, old.getVersion());
        var wrapper = new BeanWrapperImpl(next);
        for (String field :
                List.of(
                        "standardId",
                        "standardCode",
                        "standardName",
                        "customerRelationshipId",
                        "customerId",
                        "customerName",
                        "effectiveStartDate",
                        "effectiveEndDate",
                        "remark",
                        "snapshotJson")) {
            Object value = wrapper.getPropertyValue(field);
            if (value == null) update.setNull(field);
            else update.set(field, value);
        }
        return update.where("id", old.getId())
                .and("version", old.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(setting(old.getProjectId(), old.getId(), false));
    }

    @Transactional
    public Mono<Void> delete(String project, String id, Long version) {
        return scope.project(project, CrudAssetPermission.delete)
                .then(periodGate.assertOpen(project, LocalDate.now()))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                setting(project, id, true)
                                        .flatMap(
                                                old -> {
                                                    requireVersion(old, version);
                                                    return AssetsHolder.assertPermission(
                                                                    SPACE,
                                                                    old.getSpaceId(),
                                                                    CrudAssetPermission.delete)
                                                            .then(requireUnbilled(old))
                                                            .then(
                                                                    audit(
                                                                                    settings
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    SubjectService
                                                                                            ::changed))
                                                            .then();
                                                }));
    }

    @Transactional
    public Mono<SaveResult> nextVersion(
            String project, String id, SettingInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, date))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                prepare(project, input)
                                        .flatMap(
                                                next ->
                                                        setting(project, id, true)
                                                                .flatMap(
                                                                        old -> {
                                                                            requireVersion(
                                                                                    old,
                                                                                    input
                                                                                            .getVersion());
                                                                            if (!old.getSpaceId()
                                                                                            .equals(
                                                                                                    next
                                                                                                            .getSpaceId())
                                                                                    || !old.getProjectSubjectId()
                                                                                            .equals(
                                                                                                    next
                                                                                                            .getProjectSubjectId())
                                                                                    || !"ACTIVE"
                                                                                            .equals(
                                                                                                    old
                                                                                                            .getStatus())
                                                                                    || next.getEffectiveStartDate()
                                                                                                    .compareTo(
                                                                                                            old
                                                                                                                    .getEffectiveStartDate())
                                                                                            <= 0)
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "setting_version_invalid",
                                                                                                409));
                                                                            String end =
                                                                                    LocalDate.parse(
                                                                                                    next
                                                                                                            .getEffectiveStartDate())
                                                                                            .minusDays(
                                                                                                    1)
                                                                                            .toString();
                                                                            return bills.createQuery()
                                                                                    .where(
                                                                                            "settingId",
                                                                                            id)
                                                                                    .gte(
                                                                                            "calcEndDate",
                                                                                            next
                                                                                                    .getEffectiveStartDate())
                                                                                    .fetchOne()
                                                                                    .hasElement()
                                                                                    .flatMap(
                                                                                            affected -> {
                                                                                                if (affected)
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "setting_version_bills_overlap",
                                                                                                                            409));
                                                                                                var
                                                                                                        update =
                                                                                                                audit(
                                                                                                                                settings
                                                                                                                                        .createUpdate(),
                                                                                                                                auth,
                                                                                                                                old
                                                                                                                                        .getVersion())
                                                                                                                        .set(
                                                                                                                                "status",
                                                                                                                                "SUPERSEDED");
                                                                                                if (old
                                                                                                                        .getEffectiveEndDate()
                                                                                                                == null
                                                                                                        || old.getEffectiveEndDate()
                                                                                                                        .compareTo(
                                                                                                                                end)
                                                                                                                > 0)
                                                                                                    update
                                                                                                            .set(
                                                                                                                    "effectiveEndDate",
                                                                                                                    end);
                                                                                                return assertNoOverlap(
                                                                                                                next,
                                                                                                                id)
                                                                                                        .then(
                                                                                                                update.where(
                                                                                                                                "id",
                                                                                                                                id)
                                                                                                                        .and(
                                                                                                                                "version",
                                                                                                                                old
                                                                                                                                        .getVersion())
                                                                                                                        .execute()
                                                                                                                        .flatMap(
                                                                                                                                SubjectService
                                                                                                                                        ::changed))
                                                                                                        .then(
                                                                                                                Mono
                                                                                                                        .defer(
                                                                                                                                () -> {
                                                                                                                                    next
                                                                                                                                            .setPreviousVersionId(
                                                                                                                                                    id);
                                                                                                                                    return insert(
                                                                                                                                            next,
                                                                                                                                            auth);
                                                                                                                                }));
                                                                                            });
                                                                        }))
                                        .flatMap(row -> generated(row, date, auth)))
                .as(MonoTracer.create("/property/finance/setting/next-version"));
    }

    /** 全批次先校验，再统一写入；任意一项失败回滚全部设置和应收。 */
    @Transactional
    public Mono<List<SaveResult>> createBatch(
            String project, List<SettingInput> inputs, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, date))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (inputs == null
                                                    || inputs.isEmpty()
                                                    || inputs.size() > 200)
                                                return Mono.error(
                                                        error("setting_batch_invalid", 400));
                                            // 所有锁按空间、标准顺序取得；降低多个批量请求之间的锁顺序差异。
                                            var sorted = new ArrayList<>(inputs);
                                            sorted.forEach(SettingRules::validate);
                                            sorted.sort(
                                                    Comparator.comparing(SettingInput::getSpaceId)
                                                            .thenComparing(
                                                                    SettingInput::getStandardId));
                                            return Flux.fromIterable(sorted)
                                                    .concatMap(input -> prepare(project, input))
                                                    .collectList()
                                                    .flatMap(
                                                            prepared -> {
                                                                for (int i = 0;
                                                                        i < prepared.size();
                                                                        i++)
                                                                    for (int j = i + 1;
                                                                            j < prepared.size();
                                                                            j++) {
                                                                        var a = prepared.get(i);
                                                                        var b = prepared.get(j);
                                                                        if (a.getSpaceId()
                                                                                        .equals(
                                                                                                b
                                                                                                        .getSpaceId())
                                                                                && a.getSubjectId()
                                                                                        .equals(
                                                                                                b
                                                                                                        .getSubjectId())
                                                                                && SettingRules
                                                                                        .overlaps(
                                                                                                a
                                                                                                        .getEffectiveStartDate(),
                                                                                                a
                                                                                                        .getEffectiveEndDate(),
                                                                                                b
                                                                                                        .getEffectiveStartDate(),
                                                                                                b
                                                                                                        .getEffectiveEndDate()))
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "setting_overlap",
                                                                                            409));
                                                                    }
                                                                return Flux.fromIterable(prepared)
                                                                        .concatMap(
                                                                                row ->
                                                                                        assertNoOverlap(
                                                                                                row,
                                                                                                null))
                                                                        .then(
                                                                                Flux.fromIterable(
                                                                                                prepared)
                                                                                        .concatMap(
                                                                                                row ->
                                                                                                        insert(
                                                                                                                        row,
                                                                                                                        auth)
                                                                                                                .flatMap(
                                                                                                                        saved ->
                                                                                                                                generated(
                                                                                                                                        saved,
                                                                                                                                        date,
                                                                                                                                        auth)))
                                                                                        .collectList());
                                                            });
                                        }))
                .as(MonoTracer.create("/property/finance/setting/batch"));
    }

    @Transactional
    public Mono<SettingView> stop(String project, String id, StopInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, date))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                setting(project, id, true)
                                        .flatMap(
                                                old -> {
                                                    requireVersion(
                                                            old,
                                                            input == null ? null : input.version());
                                                    var end =
                                                            SettingRules.date(
                                                                    input.endDate(), true);
                                                    if (end.isBefore(
                                                                    LocalDate.parse(
                                                                            old
                                                                                    .getEffectiveStartDate()))
                                                            || old.getEffectiveEndDate() != null
                                                                    && end.isAfter(
                                                                            LocalDate.parse(
                                                                                    old
                                                                                            .getEffectiveEndDate()))
                                                            || !"ACTIVE".equals(old.getStatus()))
                                                        return Mono.error(
                                                                error(
                                                                        "setting_invalid_dates",
                                                                        400));
                                                    return periodGate
                                                            .assertOpen(project, end)
                                                            .then(
                                                                    AssetsHolder.assertPermission(
                                                                            SPACE,
                                                                            old.getSpaceId(),
                                                                            CrudAssetPermission
                                                                                    .save))
                                                            .then(
                                                                    bills.createQuery()
                                                                            .where("settingId", id)
                                                                            .gt(
                                                                                    "calcEndDate",
                                                                                    end.toString())
                                                                            .fetchOne()
                                                                            .hasElement())
                                                            .flatMap(
                                                                    affected -> {
                                                                        if (affected)
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "setting_version_bills_overlap",
                                                                                            409));
                                                                        return audit(
                                                                                        settings
                                                                                                .createUpdate(),
                                                                                        auth,
                                                                                        old
                                                                                                .getVersion())
                                                                                .set(
                                                                                        "effectiveEndDate",
                                                                                        end
                                                                                                .toString())
                                                                                .set(
                                                                                        "status",
                                                                                        "STOPPED")
                                                                                .where("id", id)
                                                                                .and(
                                                                                        "version",
                                                                                        old
                                                                                                .getVersion())
                                                                                .execute()
                                                                                .flatMap(
                                                                                        SubjectService
                                                                                                ::changed)
                                                                                .then(
                                                                                        setting(
                                                                                                project,
                                                                                                id,
                                                                                                false))
                                                                                .flatMap(
                                                                                        row ->
                                                                                                generation
                                                                                                        .generate(
                                                                                                                row,
                                                                                                                date,
                                                                                                                auth)
                                                                                                        .then(
                                                                                                                view(
                                                                                                                        row)));
                                                                    });
                                                }))
                .as(MonoTracer.create("/property/finance/setting/stop"));
    }

    Mono<HouseSettingEntity> setting(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return settings.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_not_found", 404)));
    }

    private static void requireVersion(HouseSettingEntity row, Long version) {
        if (!Objects.equals(row.getVersion(), version)) throw error("version_conflict", 409);
    }

    public record SaveResult(SettingView setting, int generated, int retained) {}

    public record StopInput(Long version, String endDate) {}
}
