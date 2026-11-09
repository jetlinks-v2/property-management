package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.util.*;

/** 实收按核销明细授权和分页，不能先分页主单再过滤空间，避免泄漏其他房屋收款。 */
@Service
@RequiredArgsConstructor
public class PaymentRecordService {
    private final FinanceScope scope;
    private final HandoverStateQuery handoverStates;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<PaymentEntity, String> payments;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<PagerResult<View>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(handoverStates.active(List.of(project)))
                .flatMap(
                        snapshot -> {
                            var query = SubjectService.page(input);
                            // 派生状态先转换为来源绑定子查询，total和分页仍由框架执行。
                            query.setTerms(snapshot.terms(query.getTerms()));
                            query.setForUpdate(false);
                            query.toNestQuery(
                                    nested ->
                                            nested.and("projectId", project).and("deleted", false));
                            if (query.getSorts().isEmpty())
                                query.toQuery().orderByDesc("createTime").orderByDesc("id");
                            return FinanceSpaceQuery.inject(query, lines, CrudAssetPermission.read)
                                    .flatMap(
                                            secured ->
                                                    QueryHelper.transformPageResult(
                                                            QueryHelper.queryPager(
                                                                    secured, lines::createQuery),
                                                            rows -> {
                                                                if (rows.isEmpty())
                                                                    return Mono.just(
                                                                            List.<View>of());
                                                                var ids =
                                                                        rows.stream()
                                                                                .map(
                                                                                        PaymentAllocationEntity
                                                                                                ::getPaymentId)
                                                                                .distinct()
                                                                                .toList();
                                                                return Mono.zip(
                                                                                handoverStates
                                                                                        .payments(
                                                                                                snapshot,
                                                                                                ids),
                                                                                payments.createQuery()
                                                                                        .where(
                                                                                                "projectId",
                                                                                                project)
                                                                                        .and(
                                                                                                "deleted",
                                                                                                false)
                                                                                        .in(
                                                                                                "id",
                                                                                                ids)
                                                                                        .fetch()
                                                                                        .collectMap(
                                                                                                PaymentEntity
                                                                                                        ::getId))
                                                                        .map(
                                                                                result ->
                                                                                        rows
                                                                                                .stream()
                                                                                                .map(
                                                                                                        line -> {
                                                                                                            var
                                                                                                                    payment =
                                                                                                                            result.getT2()
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
                                                                                                                            .getBillNo(),
                                                                                                                    payment
                                                                                                                            .getPaidAt(),
                                                                                                                    payment
                                                                                                                            .getPayerName(),
                                                                                                                    payment
                                                                                                                            .getCompanyName(),
                                                                                                                    payment
                                                                                                                            .getModifierName(),
                                                                                                                    result.getT1()
                                                                                                                            .getOrDefault(
                                                                                                                                    line
                                                                                                                                            .getPaymentId(),
                                                                                                                                    "UNHANDED"));
                                                                                                        })
                                                                                                .toList());
                                                            }));
                        });
    }

    public record View(
            PaymentAllocationEntity line,
            String billNo,
            String paidAt,
            String payerName,
            String companyName,
            String operatorName,
            String handoverState) {}
}
