package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.DocumentRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

/** 可开具来源只从规范应收/临时主单读取；分页前授权，分页后复用冻结快照与精确额度校验。 */
@Service
@RequiredArgsConstructor
public class DocumentCandidateService {
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final DocumentSourceService sources;
    private final DocumentRuleService settings;
    private final DocumentRules rules;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<FundEntryEntity, String> fundEntries;

    public Mono<PagerResult<View>> query(
            String project, String type, Input input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    validate(type, input, now);
                                    return settings.effective(project, false)
                                            .flatMap(
                                                    rule ->
                                                            "RECEIVABLE".equals(input.sourceType())
                                                                    ? receivables(
                                                                            project, type, input,
                                                                            rule, now)
                                                                    : "FUND_TOPUP"
                                                                                    .equals(
                                                                                            input
                                                                                                    .sourceType())
                                                                            ? originalFunds(
                                                                                    project, type,
                                                                                    input, rule,
                                                                                    now)
                                                                            : temporary(
                                                                                    project, type,
                                                                                    input, rule,
                                                                                    now));
                                }))
                .as(MonoTracer.create("property.finance.document.sources"));
    }

    private Mono<PagerResult<View>> receivables(
            String project,
            String type,
            Input input,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        var q = page(input);
        q.toQuery()
                .and("projectId", project)
                .and("deleted", false)
                .nest()
                .isNull("split")
                .or()
                .is("split", false)
                .end();
        filters(q, input);
        // 标准/公司只取历史JSON快照，不用当前标准关联替换。当前服务的业务数据库为PostgreSQL。
        if (input.companyId() != null)
            q.toQuery()
                    .accept(
                            SqlTerm.of(
                                    "cast(snapshot_json as jsonb) -> 'standard' ->> 'companyId' ="
                                            + " ?",
                                    input.companyId()));
        return visibility
                .inject(q, bills, CrudAssetPermission.save)
                .flatMap(
                        secured ->
                                QueryHelper.transformPageResult(
                                        QueryHelper.queryPager(secured, bills::createQuery),
                                        rows ->
                                                views(
                                                        project,
                                                        type,
                                                        rows.stream()
                                                                .map(
                                                                        row ->
                                                                                new DocumentSourceService
                                                                                        .Reference(
                                                                                        "RECEIVABLE",
                                                                                        row
                                                                                                .getId()))
                                                                .toList(),
                                                        rule,
                                                        now)));
    }

    private Mono<PagerResult<View>> temporary(
            String project,
            String type,
            Input input,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        var match = new QueryParamEntity();
        match.setPaging(false);
        match.toQuery().and("projectId", project).and("deleted", false);
        filters(match, input);
        var matching = lines.nativeQuery().select("paymentId").setParam(match).getSql();
        return visibility
                .payments(project)
                .flatMap(
                        permitted -> {
                            var q = page(input);
                            q.toQuery()
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .and("sourceType", "TEMPORARY")
                                    .and("status", "SUCCEEDED")
                                    .accept(permitted)
                                    .accept(
                                            SqlTerm.of(
                                                    "id in (" + matching.getSql() + ")",
                                                    matching.getParameters()));
                            if (input.companyId() != null)
                                q.toQuery().and("companyId", input.companyId());
                            return QueryHelper.transformPageResult(
                                    QueryHelper.queryPager(q, payments::createQuery),
                                    rows ->
                                            views(
                                                    project,
                                                    type,
                                                    rows.stream()
                                                            .map(
                                                                    row ->
                                                                            new DocumentSourceService
                                                                                    .Reference(
                                                                                    "TEMP_PAYMENT",
                                                                                    row.getId()))
                                                            .toList(),
                                                    rule,
                                                    now));
                        });
    }

    private Mono<List<View>> views(
            String project,
            String type,
            List<DocumentSourceService.Reference> refs,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        if (refs.isEmpty()) return Mono.just(List.of());
        // 损坏快照、权限或来源失败明确拒绝整次查询，不静默跳过后伪装成成功空页。
        return sources.load(project, refs, false)
                .map(rows -> rows.stream().map(row -> view(type, row, rule, now)).toList());
    }

    private Mono<PagerResult<View>> originalFunds(
            String project,
            String type,
            Input input,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        var q = page(input);
        q.toQuery()
                .and("projectId", project)
                .and("deleted", false)
                .and("action", "TOPUP")
                .accept(
                        SqlTerm.of(
                                "cast(document_snapshot_json as jsonb) ->> 'fundType' = ?",
                                "ADVANCE"))
                .gt("amount", 0);
        // 客户ID来自每笔存入的不可变快照，不能关联到可变账户的当前客户或标准。
        if (input.customerId() != null)
            q.toQuery()
                    .accept(
                            SqlTerm.of(
                                    "cast(document_snapshot_json as jsonb) ->> 'customerId' = ?",
                                    input.customerId()));
        if (input.companyId() != null) q.toQuery().and("companyId", input.companyId());
        if (input.spaceIds() != null && !input.spaceIds().isEmpty())
            q.toQuery().in("spaceId", input.spaceIds());
        if (input.keywords() != null && !input.keywords().isBlank()) {
            var keyword = "%" + input.keywords().trim() + "%";
            q.toQuery()
                    .nest()
                    .like("fullName", keyword)
                    .or()
                    .like("customerName", keyword)
                    .or()
                    .like("subjectName", keyword)
                    .end();
        }
        // 已消费原存入不再是候选。SQL只使用本财务模块的控制账，不跨基础表。
        q.toQuery()
                .accept(
                        SqlTerm.of(
                                "id in (select entry_id from pm_fin_fund_credit where project_id ="
                                        + " ? and deleted = false and remaining_amount ="
                                        + " original_amount)",
                                project));
        return visibility
                .inject(q, fundEntries, CrudAssetPermission.save)
                .flatMap(
                        secured ->
                                QueryHelper.transformPageResult(
                                        QueryHelper.queryPager(secured, fundEntries::createQuery),
                                        rows ->
                                                views(
                                                        project,
                                                        type,
                                                        rows.stream()
                                                                .map(
                                                                        row ->
                                                                                new DocumentSourceService
                                                                                        .Reference(
                                                                                        "FUND_TOPUP",
                                                                                        row
                                                                                                .getId()))
                                                                .toList(),
                                                        rule,
                                                        now)));
    }

    private View view(
            String type,
            DocumentSourceService.Source row,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        String unavailable = null;
        if (row.availableAmount().signum() == 0)
            unavailable = "error.property_finance_document_quota_insufficient";
        else if ("INVOICE".equals(type)
                && !rules.withinTime(row.paidAt(), now, rule.timeLimitType(), rule.limitDays()))
            unavailable = "error.property_finance_document_expired";
        return new View(
                row.canonical().type(),
                row.canonical().id(),
                row.spaceId(),
                row.fullName(),
                row.customerId(),
                row.customerName(),
                row.subjectName(),
                row.standardName(),
                row.startDate(),
                row.endDate(),
                row.tax().companyId(),
                row.tax().companyName(),
                row.allowedAmount().toPlainString(),
                row.paidAmount().toPlainString(),
                row.availableAmount().toPlainString(),
                row.paidAt() == null ? null : row.paidAt().toString(),
                row.tax().taxRate(),
                row.tax().goodsCode(),
                unavailable == null,
                unavailable,
                row.fund() == null ? null : row.fund().account().getType());
    }

    private static void filters(QueryParamEntity q, Input input) {
        if (input.customerId() != null) q.toQuery().and("customerId", input.customerId());
        if (input.spaceIds() != null && !input.spaceIds().isEmpty())
            q.toQuery().in("spaceId", input.spaceIds());
        if (input.keywords() != null && !input.keywords().isBlank()) {
            var words = "%" + input.keywords().trim() + "%";
            q.toQuery()
                    .nest()
                    .like("fullName", words)
                    .or()
                    .like("customerName", words)
                    .or()
                    .like("subjectName", words)
                    .end();
        }
    }

    private static QueryParamEntity page(Input input) {
        var q = new QueryParamEntity();
        q.setPageIndex(input.pageIndex() == null ? 0 : Math.max(0, input.pageIndex()));
        q.setPageSize(input.pageSize() == null ? 12 : Math.min(200, Math.max(1, input.pageSize())));
        q.setPaging(true);
        q.setForUpdate(false);
        q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }

    private static void validate(String type, Input input, LocalDateTime now) {
        DocumentStore.validateType(type);
        if (input == null
                || now == null
                || !Set.of("RECEIVABLE", "TEMP_PAYMENT", "FUND_TOPUP")
                        .contains(Objects.toString(input.sourceType(), ""))
                || input.companyId() != null && !id(input.companyId())
                || input.customerId() != null && !id(input.customerId())
                || input.keywords() != null && input.keywords().length() > 255
                || input.spaceIds() != null
                        && (input.spaceIds().size() > 1000
                                || input.spaceIds().stream().anyMatch(room -> !id(room))))
            throw error("document_candidate_invalid", 400);
    }

    private static boolean id(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.trim());
    }

    public record Input(
            String sourceType,
            String companyId,
            String customerId,
            List<String> spaceIds,
            String keywords,
            Integer pageIndex,
            Integer pageSize) {}

    public record View(
            String sourceType,
            String sourceId,
            String spaceId,
            String fullName,
            String customerId,
            String customerName,
            String subjectName,
            String standardName,
            String startDate,
            String endDate,
            String companyId,
            String companyName,
            String allowedAmount,
            String paidAmount,
            String availableAmount,
            String paidAt,
            String taxRate,
            String goodsCode,
            boolean selectable,
            String unavailableCode,
            String fundType) {}
}
