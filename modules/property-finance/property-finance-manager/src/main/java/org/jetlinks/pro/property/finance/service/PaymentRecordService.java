package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.util.*;

/** 实收按核销明细授权和分页，不能先分页主单再过滤空间，避免泄漏其他房屋收款。 */
@Service
@RequiredArgsConstructor
public class PaymentRecordService {
    private final FinanceScope scope;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<PaymentEntity, String> payments;

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
                                                                        return payments.createQuery()
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
                                                                                                        PaymentAllocationEntity
                                                                                                                ::getPaymentId)
                                                                                                .distinct()
                                                                                                .toList())
                                                                                .fetch()
                                                                                .collectMap(
                                                                                        PaymentEntity
                                                                                                ::getId)
                                                                                .map(
                                                                                        index ->
                                                                                                rows
                                                                                                        .stream()
                                                                                                        .map(
                                                                                                                line -> {
                                                                                                                    var
                                                                                                                            payment =
                                                                                                                                    index
                                                                                                                                            .get(
                                                                                                                                                    line
                                                                                                                                                            .getPaymentId());
                                                                                                                    if (payment
                                                                                                                            == null)
                                                                                                                        throw FinanceScope
                                                                                                                                .error(
                                                                                                                                        "payment_not_found",
                                                                                                                                        409);
                                                                                                                    return new View(
                                                                                                                            line,
                                                                                                                            payment
                                                                                                                                    .getPaidAt(),
                                                                                                                            payment
                                                                                                                                    .getPayerName(),
                                                                                                                            payment
                                                                                                                                    .getCompanyName(),
                                                                                                                            payment
                                                                                                                                    .getModifierName());
                                                                                                                })
                                                                                                        .toList());
                                                                    }));
                                }));
    }

    public record View(
            PaymentAllocationEntity line,
            String paidAt,
            String payerName,
            String companyName,
            String operatorName) {}
}
