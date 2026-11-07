package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 月结来源只在期间门闩内冻结；金额来自准备器，来源归属与现有关闭标记分别记录。 */
@Component
@RequiredArgsConstructor
public class MonthClosingSources {
    static final int LIMIT = 10000;
    private final MonthClosingPreparation preparation;
    private final HandoverSources sourceReader;
    private final DocumentStore documentStore;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<FundCreditEntity, String> credits;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<HandoverEntity, String> handovers;
    private final ReactiveRepository<HandoverLineEntity, String> handoverLines;
    private final ReactiveRepository<MonthClosingMemberEntity, String> members;

    record Member(
            String type,
            String id,
            Long version,
            Boolean previousClosed,
            List<String> spaces,
            String detail) {
        Object fingerprint() {
            return Arrays.asList(type, id, version, previousClosed, spaces, detail);
        }
    }

    record Snapshot(
            MonthClosingPreparation.Result sources,
            List<Member> members,
            int receivableCount,
            int pendingReceivableCount) {}

    Mono<Snapshot> prepare(String project, String tenant, AccountingPeriodEntity period) {
        var dates =
                new MonthClosingPreparation.Period(
                        period.getAccountMonth(),
                        LocalDate.parse(period.getStartDate()),
                        LocalDate.parse(period.getEndDate()));
        return preparation
                .prepare(project, dates)
                .flatMap(
                        prepared ->
                                Mono.zip(
                                                paymentMembers(project, prepared),
                                                fundMembers(project, dates),
                                                receivableMembers(project, dates),
                                                documentMembers(project, tenant, dates),
                                                handoverMembers(project, tenant, dates, prepared))
                                        .map(
                                                parts ->
                                                        assemble(
                                                                prepared,
                                                                parts.getT1(),
                                                                parts.getT2(),
                                                                parts.getT3(),
                                                                parts.getT4(),
                                                                parts.getT5())));
    }

    private Snapshot assemble(
            MonthClosingPreparation.Result prepared,
            List<Member> payments,
            List<Member> funds,
            List<Member> receivables,
            List<Member> documents,
            List<Member> handovers) {
        var all = new ArrayList<Member>();
        for (var group : List.of(payments, funds, receivables, documents, handovers))
            all.addAll(group);
        var ordered =
                bounded(all).stream()
                        .sorted(Comparator.comparing(Member::type).thenComparing(Member::id))
                        .toList();
        int pending =
                (int)
                        receivables.stream()
                                .filter(
                                        m ->
                                                !"APPROVED"
                                                        .equals(
                                                                JSON.parseObject(m.detail())
                                                                        .getString("reviewState")))
                                .count();
        return new Snapshot(prepared, ordered, receivables.size(), pending);
    }

    private Mono<List<Member>> paymentMembers(
            String project, MonthClosingPreparation.Result prepared) {
        // FUND 在全量流水中单独记录；关联缴款/退款的流水保留归属，但不重复计入资金合计。
        return Flux.fromIterable(prepared.sources())
                .filter(s -> !"FUND".equals(s.type()))
                .concatMap(
                        s ->
                                sourceReader
                                        .load(
                                                project,
                                                new HandoverSources.Item(
                                                        s.type(), s.id(), s.version()),
                                                false,
                                                CrudAssetPermission.save)
                                        .map(
                                                source ->
                                                        member(
                                                                s.type(),
                                                                s.id(),
                                                                s.version(),
                                                                null,
                                                                source.lines().stream()
                                                                        .map(
                                                                                HandoverLineEntity
                                                                                        ::getSpaceId)
                                                                        .toList(),
                                                                Map.of(
                                                                        "amount",
                                                                        s.amount(),
                                                                        "cashAmount",
                                                                        s.cashAmount()))))
                .collectList();
    }

    private Mono<List<Member>> fundMembers(String project, MonthClosingPreparation.Period period) {
        return entries.createQuery()
                .setParam(range(project, "occurredAt", period, true))
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            bounded(rows);
                            var byId = new HashMap<String, FundEntryEntity>();
                            rows.forEach(row -> byId.put(row.getId(), row));
                            return Flux.fromIterable(rows)
                                    .concatMap(
                                            row ->
                                                    permission(List.of(row.getSpaceId()))
                                                            .thenReturn(
                                                                    member(
                                                                            "FUND",
                                                                            row.getId(),
                                                                            row.getVersion(),
                                                                            null,
                                                                            List.of(
                                                                                    row
                                                                                            .getSpaceId()),
                                                                            Map.of(
                                                                                    "amount",
                                                                                    row.getAmount()
                                                                                            .toPlainString(),
                                                                                    "cashAmount",
                                                                                    row.getCashAmount()
                                                                                            .toPlainString(),
                                                                                    "action",
                                                                                    row.getAction(),
                                                                                    "occurredAt",
                                                                                    row
                                                                                            .getOccurredAt()))))
                                    .collectList()
                                    .flatMap(
                                            result -> {
                                                if (rows.isEmpty()) return Mono.just(result);
                                                return credits.createQuery()
                                                        .setParam(limit())
                                                        .where("projectId", project)
                                                        .and("deleted", false)
                                                        .in("entryId", byId.keySet())
                                                        .fetch()
                                                        .collectList()
                                                        .map(
                                                                found -> {
                                                                    bounded(found);
                                                                    for (var row : found) {
                                                                        var entry =
                                                                                byId.get(
                                                                                        row
                                                                                                .getEntryId());
                                                                        result.add(
                                                                                member(
                                                                                        "CREDIT",
                                                                                        row.getId(),
                                                                                        row
                                                                                                .getVersion(),
                                                                                        row
                                                                                                .getMonthClosed(),
                                                                                        List.of(
                                                                                                entry
                                                                                                        .getSpaceId()),
                                                                                        Map.of(
                                                                                                "entryId",
                                                                                                row
                                                                                                        .getEntryId(),
                                                                                                "remainingAmount",
                                                                                                row.getRemainingAmount()
                                                                                                        .toPlainString())));
                                                                    }
                                                                    return bounded(result);
                                                                });
                                            });
                        });
    }

    private Mono<List<Member>> receivableMembers(
            String project, MonthClosingPreparation.Period period) {
        var query = range(project, "dueDate", period, false);
        // split 沿现有应收查询的历史语义：null 和 false 都是有效原账，只有 true 才排除。
        query.toQuery().nest().isNull("split").or().is("split", false).end();
        return bills.createQuery()
                .setParam(query)
                .fetch()
                .collectList()
                .flatMap(
                        rows ->
                                Flux.fromIterable(bounded(rows))
                                        .concatMap(
                                                row ->
                                                        permission(List.of(row.getSpaceId()))
                                                                .thenReturn(
                                                                        member(
                                                                                "RECEIVABLE",
                                                                                row.getId(),
                                                                                row.getVersion(),
                                                                                row
                                                                                        .getFinancialClosed(),
                                                                                List.of(
                                                                                        row
                                                                                                .getSpaceId()),
                                                                                Map.of(
                                                                                        "reviewState",
                                                                                        Objects
                                                                                                .toString(
                                                                                                        row
                                                                                                                .getReviewState(),
                                                                                                        "PENDING"),
                                                                                        "amount",
                                                                                        row.getActualReceivableAmount()
                                                                                                .toPlainString(),
                                                                                        "dueDate",
                                                                                        row
                                                                                                .getDueDate()))))
                                        .collectList());
    }

    private Mono<List<Member>> documentMembers(
            String project, String tenant, MonthClosingPreparation.Period period) {
        return documents
                .createQuery()
                .setParam(range(project, "issuedAt", period, true))
                .and("tenantId", tenant)
                .fetch()
                .collectList()
                .flatMap(
                        rows ->
                                Flux.fromIterable(bounded(rows))
                                        .concatMap(
                                                row ->
                                                        documentStore
                                                                .lines(
                                                                        row,
                                                                        CrudAssetPermission.save)
                                                                .map(
                                                                        lines ->
                                                                                member(
                                                                                        "DOCUMENT",
                                                                                        row.getId(),
                                                                                        row
                                                                                                .getVersion(),
                                                                                        null,
                                                                                        lines
                                                                                                .stream()
                                                                                                .map(
                                                                                                        DocumentLineEntity
                                                                                                                ::getSpaceId)
                                                                                                .toList(),
                                                                                        Map.of(
                                                                                                "status",
                                                                                                row
                                                                                                        .getStatus(),
                                                                                                "issuedAt",
                                                                                                row
                                                                                                        .getIssuedAt(),
                                                                                                "documentType",
                                                                                                row
                                                                                                        .getDocumentType(),
                                                                                                "number",
                                                                                                row
                                                                                                        .getDocumentNumber()))))
                                        .collectList());
    }

    private Mono<List<Member>> handoverMembers(
            String project,
            String tenant,
            MonthClosingPreparation.Period period,
            MonthClosingPreparation.Result prepared) {
        var ids =
                prepared.sources().stream()
                        .map(MonthClosingPreparation.PreparedSource::handoverId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        var linked =
                ids.isEmpty()
                        ? Flux.<HandoverEntity>empty()
                        : handovers
                                .createQuery()
                                .setParam(limit())
                                .where("projectId", project)
                                .and("tenantId", tenant)
                                .and("deleted", false)
                                .in("id", ids)
                                .fetch();
        return Flux.concat(
                        handovers
                                .createQuery()
                                .setParam(range(project, "closingDate", period, false))
                                .and("tenantId", tenant)
                                .in("status", List.of("PENDING", "APPROVED"))
                                .fetch(),
                        linked)
                .collectList()
                .flatMap(
                        rows -> {
                            var unique = new TreeMap<String, HandoverEntity>();
                            rows.forEach(row -> unique.put(row.getId(), row));
                            bounded(new ArrayList<>(unique.values()));
                            return Flux.fromIterable(unique.values())
                                    .concatMap(
                                            row -> {
                                                return handoverLines
                                                        .createQuery()
                                                        .setParam(limit())
                                                        .where("projectId", project)
                                                        .and("batchId", row.getId())
                                                        .and("deleted", false)
                                                        .fetch()
                                                        .collectList()
                                                        .flatMap(
                                                                lines -> {
                                                                    bounded(lines);
                                                                    if (lines.isEmpty())
                                                                        return Mono.error(
                                                                                error(
                                                                                        "month_closing_source_invalid",
                                                                                        409));
                                                                    return sourceReader
                                                                            .authorize(
                                                                                    lines,
                                                                                    CrudAssetPermission
                                                                                            .save)
                                                                            .thenReturn(
                                                                                    member(
                                                                                            "HANDOVER",
                                                                                            row
                                                                                                    .getId(),
                                                                                            row
                                                                                                    .getVersion(),
                                                                                            row
                                                                                                    .getMonthClosed(),
                                                                                            lines
                                                                                                    .stream()
                                                                                                    .map(
                                                                                                            HandoverLineEntity
                                                                                                                    ::getSpaceId)
                                                                                                    .toList(),
                                                                                            Map.of(
                                                                                                    "amount",
                                                                                                    row.getAmount()
                                                                                                            .toPlainString(),
                                                                                                    "status",
                                                                                                    row
                                                                                                            .getStatus(),
                                                                                                    "closingDate",
                                                                                                    row
                                                                                                            .getClosingDate())));
                                                                });
                                            })
                                    .collectList();
                        });
    }

    private Member member(
            String type,
            String id,
            Long version,
            Boolean previous,
            List<String> spaces,
            Object detail) {
        if (id == null
                || version == null
                || spaces.isEmpty()
                || spaces.stream().anyMatch(Objects::isNull))
            throw error("month_closing_source_invalid", 409);
        return new Member(
                type,
                id,
                version,
                previous,
                spaces.stream().distinct().sorted().toList(),
                JSON.toJSONString(detail));
    }

    Mono<Void> save(
            String project,
            String tenant,
            String operationId,
            List<Member> source,
            Authentication auth) {
        var rows =
                source.stream()
                        .map(
                                m -> {
                                    var row = new MonthClosingMemberEntity();
                                    row.setId(UUID.randomUUID().toString());
                                    InvoiceCompanyService.init(row, auth);
                                    row.setTenantId(tenant);
                                    row.setProjectId(project);
                                    row.setOperationId(operationId);
                                    row.setSourceType(m.type());
                                    row.setSourceId(m.id());
                                    row.setSourceVersion(m.version());
                                    row.setPreviousClosed(m.previousClosed());
                                    row.setSpaceIdsJson(JSON.toJSONString(m.spaces()));
                                    row.setDetailJson(m.detail());
                                    return row;
                                })
                        .toList();
        return Flux.fromIterable(rows).buffer(500).concatMap(members::insertBatch).then();
    }

    Mono<List<Member>> restore(String project, String tenant, String operation, int expected) {
        return restore(project, tenant, operation, expected, CrudAssetPermission.save);
    }

    Mono<List<Member>> restore(
            String project,
            String tenant,
            String operation,
            int expected,
            CrudAssetPermission permission) {
        return members.createQuery()
                .setParam(limit())
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("operationId", operation)
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (expected < 0 || expected > LIMIT || rows.size() != expected)
                                return Mono.error(error("month_closing_source_invalid", 409));
                            return Flux.fromIterable(rows)
                                    .map(
                                            row -> {
                                                List<String> spaces;
                                                try {
                                                    spaces =
                                                            JSON.parseArray(
                                                                    row.getSpaceIdsJson(),
                                                                    String.class);
                                                } catch (
                                                        com.alibaba.fastjson.JSONException
                                                                invalid) {
                                                    throw error(
                                                            "month_closing_source_invalid", 409);
                                                }
                                                if (spaces == null
                                                        || !Set.of(
                                                                        "PAYMENT",
                                                                        "REFUND",
                                                                        "FUND",
                                                                        "CREDIT",
                                                                        "RECEIVABLE",
                                                                        "DOCUMENT",
                                                                        "HANDOVER")
                                                                .contains(row.getSourceType()))
                                                    throw error(
                                                            "month_closing_source_invalid", 409);
                                                return member(
                                                        row.getSourceType(),
                                                        row.getSourceId(),
                                                        row.getSourceVersion(),
                                                        row.getPreviousClosed(),
                                                        spaces,
                                                        JSON.parseObject(row.getDetailJson()));
                                            })
                                    .sort(
                                            Comparator.comparing(Member::type)
                                                    .thenComparing(Member::id))
                                    .concatMap(
                                            m -> permission(m.spaces(), permission).thenReturn(m))
                                    .collectList();
                        });
    }

    /** 反结只恢复本轮改变的标记，使用当前版本CAS，不回滚后续合法资金事实或余额。 */
    Mono<Void> flags(String project, List<Member> rows, boolean close, Authentication auth) {
        return Flux.fromIterable(rows)
                .concatMap(
                        row ->
                                switch (row.type()) {
                                    case "RECEIVABLE" ->
                                            flag(
                                                    project,
                                                    row,
                                                    bills,
                                                    "financialClosed",
                                                    close,
                                                    auth);
                                    case "CREDIT" ->
                                            flag(project, row, credits, "monthClosed", close, auth);
                                    case "HANDOVER" ->
                                            flag(
                                                    project,
                                                    row,
                                                    handovers,
                                                    "monthClosed",
                                                    close,
                                                    auth);
                                    default -> Mono.empty();
                                })
                .then();
    }

    private <T extends FinanceEntity> Mono<Void> flag(
            String project,
            Member member,
            ReactiveRepository<T, String> repository,
            String field,
            boolean close,
            Authentication auth) {
        var q = limit();
        q.setForUpdate(true);
        return repository
                .createQuery()
                .setParam(q)
                .where("id", member.id())
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("month_closing_source_invalid", 409)))
                .flatMap(
                        current -> {
                            boolean currentFlag =
                                    switch (member.type()) {
                                        case "RECEIVABLE" ->
                                                Boolean.TRUE.equals(
                                                        ((ReceivableEntity) current)
                                                                .getFinancialClosed());
                                        case "CREDIT" ->
                                                Boolean.TRUE.equals(
                                                        ((FundCreditEntity) current)
                                                                .getMonthClosed());
                                        default ->
                                                Boolean.TRUE.equals(
                                                        ((HandoverEntity) current)
                                                                .getMonthClosed());
                                    };
                            if (close && !Objects.equals(member.version(), current.getVersion())
                                    || !close && !currentFlag)
                                return Mono.error(error("month_closing_source_invalid", 409));
                            boolean target = close || Boolean.TRUE.equals(member.previousClosed());
                            if (target == currentFlag) return Mono.empty();
                            // 冻结、解冻只改变月结标记，不能迁移原始账务的租户归属。
                            var update =
                                    InvoiceCompanyService.audit(
                                            repository.createUpdate(), auth, current.getVersion());
                            if (current instanceof ReceivableEntity bill)
                                update.set("tenantId", Objects.toString(bill.getTenantId(), ""));
                            else if (current instanceof HandoverEntity handover)
                                update.set("tenantId", handover.getTenantId());
                            return update.set(field, target)
                                    .where("id", current.getId())
                                    .and("projectId", project)
                                    .and("version", current.getVersion())
                                    .and("deleted", false)
                                    .execute()
                                    .flatMap(SubjectService::changed)
                                    .then();
                        });
    }

    private Mono<Void> permission(List<String> spaces) {
        return permission(spaces, CrudAssetPermission.save);
    }

    private Mono<Void> permission(List<String> spaces, CrudAssetPermission permission) {
        if (spaces.isEmpty() || spaces.stream().anyMatch(s -> s == null || s.isBlank()))
            return Mono.error(error("month_closing_source_invalid", 409));
        // 批量资产沿 HandoverSources/DocumentStore 的 holder 集合接口，不把整个 List 当单个资产ID。
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(holder -> holder.assertPermission("space", spaces, permission));
    }

    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > LIMIT) throw error("month_closing_source_limit", 409);
        return rows;
    }

    private static QueryParamEntity range(
            String project, String field, MonthClosingPreparation.Period period, boolean time) {
        var q = limit();
        q.toQuery()
                .and("projectId", project)
                .and("deleted", false)
                .gte(field, period.start() + (time ? "T00:00" : ""))
                .lt(field, period.end().plusDays(1) + (time ? "T00:00" : ""));
        return q;
    }

    static QueryParamEntity limit() {
        var q = new QueryParamEntity();
        q.setPageSize(LIMIT + 1);
        q.orderBy("id");
        return q;
    }
}
