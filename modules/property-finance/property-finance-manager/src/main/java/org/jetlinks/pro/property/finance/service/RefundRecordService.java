package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.util.*;

/** 退款查询先按空间授权明细，再关联主单摘要，不泄漏无权限房屋或整单金额。 */
@Service
@RequiredArgsConstructor
public class RefundRecordService {
    private final FinanceScope scope;
    private final ReactiveRepository<RefundAllocationEntity, String> lines;
    private final ReactiveRepository<RefundEntity, String> refunds;

    public Mono<PagerResult<View>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.setForUpdate(false);
                                    query.toNestQuery(
                                            nested ->
                                                    nested.and("projectId", project)
                                                            .and("deleted", false));
                                    if (query.getSorts().isEmpty())
                                        query.toQuery().orderByDesc("createTime").orderByDesc("id");
                                    return AssetsHolder.injectQueryParam(
                                                    query, AssetType.of("space"), "spaceId")
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.transformPageResult(
                                                                    QueryHelper.queryPager(
                                                                            secured,
                                                                            lines::createQuery),
                                                                    rows -> {
                                                                        if (rows.isEmpty())
                                                                            return Mono.just(
                                                                                    List
                                                                                            .<View>
                                                                                                    of());
                                                                        return refunds.createQuery()
                                                                                .where(
                                                                                        "projectId",
                                                                                        project)
                                                                                .and(
                                                                                        "deleted",
                                                                                        false)
                                                                                .in(
                                                                                        "id",
                                                                                        rows
                                                                                                .stream()
                                                                                                .map(
                                                                                                        RefundAllocationEntity
                                                                                                                ::getRefundId)
                                                                                                .distinct()
                                                                                                .toList())
                                                                                .fetch()
                                                                                .collectMap(
                                                                                        RefundEntity
                                                                                                ::getId)
                                                                                .map(
                                                                                        index ->
                                                                                                rows
                                                                                                        .stream()
                                                                                                        .map(
                                                                                                                line -> {
                                                                                                                    var
                                                                                                                            refund =
                                                                                                                                    index
                                                                                                                                            .get(
                                                                                                                                                    line
                                                                                                                                                            .getRefundId());
                                                                                                                    if (refund
                                                                                                                            == null)
                                                                                                                        throw FinanceScope
                                                                                                                                .error(
                                                                                                                                        "refund_not_found",
                                                                                                                                        409);
                                                                                                                    return new View(
                                                                                                                            line,
                                                                                                                            refund
                                                                                                                                    .getRefundedAt(),
                                                                                                                            refund
                                                                                                                                    .getReceiverName(),
                                                                                                                            refund
                                                                                                                                    .getCompanyName(),
                                                                                                                            refund
                                                                                                                                    .getMethodText(),
                                                                                                                            refund
                                                                                                                                    .getBankName(),
                                                                                                                            refund
                                                                                                                                    .getModifierName());
                                                                                                                })
                                                                                                        .toList());
                                                                    }));
                                }));
    }

    public record View(
            RefundAllocationEntity line,
            String refundedAt,
            String receiverName,
            String companyName,
            String methodText,
            String bankName,
            String operatorName) {}
}
