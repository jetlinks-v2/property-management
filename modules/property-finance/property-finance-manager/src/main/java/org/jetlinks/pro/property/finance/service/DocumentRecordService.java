package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/** 独立票据历史按整张票据分页；所有关联房屋均有权后才显示金额、总数及操作日志。 */
@Service
@RequiredArgsConstructor
public class DocumentRecordService {
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final DocumentStore store;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentOperationEntity, String> operations;

    public Mono<PagerResult<DocumentEntity>> query(
            String project, String type, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    DocumentStore.validateType(type);
                                    return TenantHolder.currentId()
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    tenant ->
                                                            documentPage(
                                                                    project, type, tenant, input));
                                }));
    }

    private Mono<PagerResult<DocumentEntity>> documentPage(
            String project, String type, String tenant, QueryParamEntity input) {
        return visibility
                .documents(project)
                .flatMap(
                        permitted -> {
                            var q = page(input);
                            // 用户OR、分页和加锁参数不能替换系统范围，权限在同一个SQL中先于分页应用。
                            q.toNestQuery(
                                    n ->
                                            n.and("projectId", project)
                                                    .and("tenantId", tenant)
                                                    .and("documentType", type)
                                                    .and("deleted", false)
                                                    .accept(permitted));
                            return QueryHelper.queryPager(q, documents::createQuery);
                        });
    }

    public Mono<PagerResult<DocumentOperationEntity>> logs(
            String project, String type, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    DocumentStore.validateType(type);
                                    return TenantHolder.currentId()
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    tenant ->
                                                            authorizedLogs(
                                                                    project, type, id, tenant,
                                                                    input));
                                }));
    }

    private Mono<PagerResult<DocumentOperationEntity>> authorizedLogs(
            String project, String type, String id, String tenant, QueryParamEntity input) {
        return store.require(project, type, id, tenant, false)
                .flatMap(row -> store.lines(row, CrudAssetPermission.read))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    // 红冲日志同时属于原蓝票及生成的红票，两个入口仍先验证自身完整空间权限。
                                    q.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("tenantId", tenant)
                                                            .and("documentType", type)
                                                            .and("deleted", false)
                                                            .nest()
                                                            .and("documentId", id)
                                                            .or("resultDocumentId", id)
                                                            .end());
                                    return QueryHelper.queryPager(q, operations::createQuery);
                                }));
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var q = SubjectService.page(input == null ? new QueryParamEntity() : input);
        q.setForUpdate(false);
        if (q.getSorts().isEmpty()) q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }
}
