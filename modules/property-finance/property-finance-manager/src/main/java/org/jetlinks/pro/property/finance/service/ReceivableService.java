package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/** 应收查询及状态维护，项目与空间双授权，历史客户和房屋以账单快照为准。 */
@Service
@RequiredArgsConstructor
public class ReceivableService {
    private static final AssetType SPACE = AssetType.of("space");
    public static final int EXPORT_LIMIT = 10000;
    private final FinanceScope scope;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    public Mono<PagerResult<ReceivableView>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(secured(project, input))
                .flatMap(
                        query ->
                                QueryHelper.transformPageResult(
                                        QueryHelper.queryPager(query, bills::createQuery),
                                        rows ->
                                                Mono.just(
                                                        rows.stream()
                                                                .map(
                                                                        row ->
                                                                                ReceivableView.of(
                                                                                        row, false))
                                                                .toList())));
    }

    private Mono<QueryParamEntity> secured(String project, QueryParamEntity input) {
        return Mono.defer(
                () -> {
                    var query = SubjectService.page(input);
                    query.setForUpdate(false);
                    // 强制约束在独立AND分组，调用方OR条件不能扩大项目或读取逻辑删除记录。
                    query.toNestQuery(
                            nested -> nested.and("projectId", project).and("deleted", false));
                    if (query.getSorts().isEmpty())
                        query.toQuery().orderByDesc("createTime").orderByDesc("id");
                    return AssetsHolder.injectQueryParam(query, SPACE, "spaceId");
                });
    }

    public Mono<ReceivableView> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, false))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                SPACE, row.getSpaceId(), CrudAssetPermission.read)
                                        .thenReturn(ReceivableView.of(row, true)));
    }

    /** 删除后仍可按原账单身份查审计，先验证原账单空间授权，绝不单凭日志ID读取。 */
    public Mono<PagerResult<ReceivableLogView>> logs(
            String project, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, true))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                        SPACE, row.getSpaceId(), CrudAssetPermission.read))
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.setForUpdate(false);
                                    query.toNestQuery(
                                            nested ->
                                                    nested.and("projectId", project)
                                                            .and("receivableId", id));
                                    if (query.getSorts().isEmpty())
                                        query.toQuery().orderByDesc("createTime").orderByDesc("id");
                                    return QueryHelper.transformPageResult(
                                            QueryHelper.queryPager(query, logs::createQuery),
                                            rows ->
                                                    Mono.just(
                                                            rows.stream()
                                                                    .map(ReceivableLogView::of)
                                                                    .toList()));
                                }));
    }

    @Transactional
    public Mono<Void> change(String project, String id, ChangeInput input, String operation) {
        return scope.project(
                        project,
                        "DELETE".equals(operation)
                                ? CrudAssetPermission.delete
                                : CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (!Set.of("FREEZE", "UNFREEZE", "DELETE")
                                                            .contains(operation)
                                                    || input == null
                                                    || input.version() == null
                                                    || input.version() < 0
                                                    || input.reason() == null
                                                    || input.reason().isBlank()
                                                    || input.reason().trim().length() > 500)
                                                return Mono.error(
                                                        error("receivable_change_invalid", 400));
                                            return row(project, id, true, false)
                                                    .flatMap(
                                                            row ->
                                                                    AssetsHolder.assertPermission(
                                                                                    SPACE,
                                                                                    row
                                                                                            .getSpaceId(),
                                                                                    "DELETE"
                                                                                                    .equals(
                                                                                                            operation)
                                                                                            ? CrudAssetPermission
                                                                                                    .delete
                                                                                            : CrudAssetPermission
                                                                                                    .save)
                                                                            .then(
                                                                                    Mono.defer(
                                                                                            () -> {
                                                                                                if (!Objects
                                                                                                        .equals(
                                                                                                                input
                                                                                                                        .version(),
                                                                                                                row
                                                                                                                        .getVersion()))
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "version_conflict",
                                                                                                                            409));
                                                                                                ReceivablePolicy
                                                                                                        .assertAllowed(
                                                                                                                row,
                                                                                                                operation);
                                                                                                var
                                                                                                        log =
                                                                                                                new ReceivableLogEntity();
                                                                                                log
                                                                                                        .setId(
                                                                                                                UUID.randomUUID()
                                                                                                                        .toString());
                                                                                                log
                                                                                                        .setProjectId(
                                                                                                                project);
                                                                                                log
                                                                                                        .setSpaceId(
                                                                                                                row
                                                                                                                        .getSpaceId());
                                                                                                log
                                                                                                        .setReceivableId(
                                                                                                                id);
                                                                                                log
                                                                                                        .setOperation(
                                                                                                                operation);
                                                                                                log
                                                                                                        .setReason(
                                                                                                                input.reason()
                                                                                                                        .trim());
                                                                                                log
                                                                                                        .setOperatorId(
                                                                                                                auth.getUser()
                                                                                                                        .getId());
                                                                                                log
                                                                                                        .setOperatorName(
                                                                                                                auth.getUser()
                                                                                                                        .getUsername());
                                                                                                log
                                                                                                        .setCreateTime(
                                                                                                                System
                                                                                                                        .currentTimeMillis());
                                                                                                log
                                                                                                        .setBeforeVersion(
                                                                                                                row
                                                                                                                        .getVersion());
                                                                                                log
                                                                                                        .setBeforeJson(
                                                                                                                auditSnapshot(
                                                                                                                        row));
                                                                                                var
                                                                                                        update =
                                                                                                                audit(
                                                                                                                        bills
                                                                                                                                .createUpdate(),
                                                                                                                        auth,
                                                                                                                        row
                                                                                                                                .getVersion());
                                                                                                if ("DELETE"
                                                                                                        .equals(
                                                                                                                operation)) {
                                                                                                    row
                                                                                                            .setDeleted(
                                                                                                                    true);
                                                                                                    row
                                                                                                            .setDeletedTime(
                                                                                                                    log
                                                                                                                            .getCreateTime());
                                                                                                    update.set(
                                                                                                                    "deleted",
                                                                                                                    true)
                                                                                                            .set(
                                                                                                                    "deletedTime",
                                                                                                                    row
                                                                                                                            .getDeletedTime());
                                                                                                } else {
                                                                                                    row
                                                                                                            .setFrozen(
                                                                                                                    "FREEZE"
                                                                                                                            .equals(
                                                                                                                                    operation));
                                                                                                    update
                                                                                                            .set(
                                                                                                                    "frozen",
                                                                                                                    row
                                                                                                                            .getFrozen());
                                                                                                }
                                                                                                var
                                                                                                        beforeVersion =
                                                                                                                row
                                                                                                                        .getVersion();
                                                                                                row
                                                                                                        .setVersion(
                                                                                                                beforeVersion
                                                                                                                        + 1);
                                                                                                row
                                                                                                        .setModifyTime(
                                                                                                                log
                                                                                                                        .getCreateTime());
                                                                                                row
                                                                                                        .setModifierId(
                                                                                                                log
                                                                                                                        .getOperatorId());
                                                                                                row
                                                                                                        .setModifierName(
                                                                                                                log
                                                                                                                        .getOperatorName());
                                                                                                log
                                                                                                        .setAfterVersion(
                                                                                                                row
                                                                                                                        .getVersion());
                                                                                                log
                                                                                                        .setAfterJson(
                                                                                                                auditSnapshot(
                                                                                                                        row));
                                                                                                return update.where(
                                                                                                                "id",
                                                                                                                id)
                                                                                                        .and(
                                                                                                                "projectId",
                                                                                                                project)
                                                                                                        .and(
                                                                                                                "version",
                                                                                                                beforeVersion)
                                                                                                        .and(
                                                                                                                "deleted",
                                                                                                                false)
                                                                                                        .execute()
                                                                                                        .flatMap(
                                                                                                                SubjectService
                                                                                                                        ::changed)
                                                                                                        .then(
                                                                                                                logs
                                                                                                                        .insert(
                                                                                                                                log))
                                                                                                        .then();
                                                                                            })));
                                        }))
                .as(MonoTracer.create("/property/finance/receivable/change"));
    }

    private static String auditSnapshot(ReceivableEntity row) {
        var snapshot = JSON.parseObject(JSON.toJSONString(row));
        snapshot.put("deleted", row.getDeleted());
        snapshot.put("deletedTime", row.getDeletedTime());
        return snapshot.toJSONString();
    }

    private Mono<ReceivableEntity> row(
            String project, String id, boolean lock, boolean includeDeleted) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        var builder = bills.createQuery().setParam(query).where("id", id).and("projectId", project);
        if (!includeDeleted) builder.and("deleted", false);
        return builder.fetchOne().switchIfEmpty(Mono.error(error("receivable_not_found", 404)));
    }

    /** 读取上限加一条以识别超限；不按分页截断后冒充完整导出。POI在有界弹性线程运行。 */
    public Mono<byte[]> export(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(secured(project, input))
                .flatMapMany(
                        query -> {
                            query.setPageIndex(0);
                            query.setPageSize(EXPORT_LIMIT + 1);
                            return bills.createQuery().setParam(query).fetch();
                        })
                .collectList()
                .flatMap(
                        rows ->
                                rows.size() > EXPORT_LIMIT
                                        ? Mono.error(error("receivable_export_limit", 400))
                                        : Mono.fromCallable(() -> ReceivableWorkbook.write(rows))
                                                .subscribeOn(Schedulers.boundedElastic()));
    }

    public record ChangeInput(Long version, String reason) {}
}
