package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.document.DocumentRules;
import org.jetlinks.pro.property.finance.document.DocumentRules.CanonicalSource;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 独立票据的共享额度账本，不提供独立预约接口。 调用方须在同一事务内先锁开票公司和所有规范来源，再调用本账本并保存票据主单。 journal与来源计数任一步失败均必须回滚，不能捕获后继续提交。
 */
@Component
@RequiredArgsConstructor
public class DocumentOccupationLedger {
    private final DocumentRules rules;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<DocumentOccupationEntity, String> occupations;
    private final ReactiveRepository<FundAccountEntity, String> fundAccounts;
    private final ReactiveRepository<FundCreditEntity, String> fundCredits;

    Mono<List<DocumentOccupationEntity>> occupy(
            String project,
            String document,
            String type,
            List<Claim> claims,
            List<DocumentSourceService.Source> sources,
            Authentication auth) {
        return Mono.defer(
                        () -> {
                            validate(document, type, claims, sources);
                            if (sources.stream()
                                    .anyMatch(source -> !project.equals(source.projectId())))
                                throw error("document_occupation_conflict", 409);
                            return rows(project, document)
                                    .flatMap(
                                            existing ->
                                                    existing.isEmpty()
                                                            ? reserveAll(
                                                                    project, document, type, claims,
                                                                    sources, auth)
                                                            : replayResult(
                                                                    existing, type, claims,
                                                                    sources));
                        })
                .as(MonoTracer.create("property.finance.document.occupy"));
    }

    private Mono<List<DocumentOccupationEntity>> replayResult(
            List<DocumentOccupationEntity> existing,
            String type,
            List<Claim> claims,
            List<DocumentSourceService.Source> sources) {
        replay(existing, type, claims, sources);
        return Mono.just(existing);
    }

    private Mono<List<DocumentOccupationEntity>> reserveAll(
            String project,
            String document,
            String type,
            List<Claim> claims,
            List<DocumentSourceService.Source> sources,
            Authentication auth) {
        var indexed = index(sources);
        // 先校验全批额度；后续CAS与journal仍在调用方同一事务，不能部分成功。
        for (var claim : claims) {
            var source = indexed.get(claim.source());
            if (money(claim.amount()).compareTo(source.availableAmount()) > 0)
                throw error("document_quota_insufficient", 409);
        }
        return Flux.fromIterable(claims)
                .concatMap(
                        claim ->
                                reserve(
                                        project,
                                        document,
                                        type,
                                        claim,
                                        indexed.get(claim.source()),
                                        auth))
                .collectList();
    }

    Mono<List<DocumentOccupationEntity>> release(
            String project,
            String document,
            String reason,
            List<DocumentSourceService.Source> sources,
            Authentication auth) {
        return Mono.defer(
                        () -> {
                            if (!id(document)
                                    || reason == null
                                    || reason.isBlank()
                                    || reason.trim().length() > 500)
                                return Mono.error(error("document_release_invalid", 400));
                            var indexed = index(sources);
                            return rows(project, document)
                                    .flatMap(
                                            existing ->
                                                    releaseAll(
                                                            existing,
                                                            reason.trim(),
                                                            indexed,
                                                            auth));
                        })
                .as(MonoTracer.create("property.finance.document.release"));
    }

    private Mono<List<DocumentOccupationEntity>> releaseAll(
            List<DocumentOccupationEntity> existing,
            String reason,
            Map<CanonicalSource, DocumentSourceService.Source> indexed,
            Authentication auth) {
        if (existing.isEmpty()) return Mono.error(error("document_occupation_not_found", 404));
        if (existing.size() != indexed.size()) throw error("document_occupation_conflict", 409);
        for (var row : existing) assertSource(row, indexed.get(key(row)));
        if (existing.stream().allMatch(row -> "RELEASED".equals(row.getState()))) {
            if (existing.stream().anyMatch(row -> !reason.equals(row.getReleaseReason())))
                throw error("document_occupation_conflict", 409);
            return Mono.just(existing);
        }
        if (existing.stream().anyMatch(row -> !"ACTIVE".equals(row.getState())))
            throw error("document_occupation_conflict", 409);
        return Flux.fromIterable(existing)
                .concatMap(row -> releaseRow(row, reason, indexed.get(key(row)), auth))
                .collectList();
    }

    private Mono<DocumentOccupationEntity> reserve(
            String project,
            String document,
            String type,
            Claim claim,
            DocumentSourceService.Source source,
            Authentication auth) {
        var amount = money(claim.amount());
        var allocation = new LinkedHashMap<String, String>();
        Mono<Void> update;
        if (source.bill() != null) {
            update = billCounter(source.bill(), amount, auth);
        } else if (source.fund() != null) {
            allocation.put(source.fund().entry().getId(), amount.toPlainString());
            update = fundCounter(source, amount, auth);
        } else {
            var remaining = amount;
            for (var line : source.paymentLines()) {
                var available =
                        money(line.getAmount())
                                .subtract(money(line.getRefundedAmount()))
                                .subtract(money(line.getInvoiceOccupiedAmount()));
                if (available.signum() < 0) throw error("document_occupation_conflict", 409);
                var share = available.min(remaining);
                if (share.signum() > 0) allocation.put(line.getId(), share.toPlainString());
                remaining = remaining.subtract(share);
            }
            if (remaining.signum() != 0) throw error("document_quota_insufficient", 409);
            update = paymentCounters(source, allocation, false, auth);
        }
        var row = new DocumentOccupationEntity();
        row.setId(
                UUID.nameUUIDFromBytes(
                                (document + ":" + claim.source().type() + ":" + claim.source().id())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString());
        init(row, auth);
        row.setProjectId(project);
        row.setDocumentId(document);
        row.setDocumentType(type);
        row.setCompanyId(source.tax().companyId());
        row.setSpaceId(source.spaceId());
        row.setSourceType(claim.source().type());
        row.setSourceId(claim.source().id());
        row.setState("ACTIVE");
        row.setAmount(amount);
        row.setAllocationJson(JSON.toJSONString(allocation));
        return update.then(occupations.insert(row)).thenReturn(row);
    }

    private Mono<DocumentOccupationEntity> releaseRow(
            DocumentOccupationEntity row,
            String reason,
            DocumentSourceService.Source source,
            Authentication auth) {
        var amount = money(row.getAmount());
        Mono<Void> update;
        if (source.bill() != null) {
            if (source.bill().getInvoiceOccupiedAmount().compareTo(amount) < 0)
                throw error("document_occupation_conflict", 409);
            update = billCounter(source.bill(), amount.negate(), auth);
        } else if (source.fund() != null) {
            if (!allocation(row.getAllocationJson())
                    .equals(Map.of(source.fund().entry().getId(), amount.toPlainString())))
                throw error("document_occupation_conflict", 409);
            update = fundCounter(source, amount.negate(), auth);
        } else {
            // 反向操作按原逐行分配恢复，不能把另一张票据的占用从不同资金来源扣除。
            var allocation = allocation(row.getAllocationJson());
            var sum =
                    allocation.values().stream()
                            .map(value -> rules.amount(value, false))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(amount) != 0) throw error("document_occupation_conflict", 409);
            update = paymentCounters(source, allocation, true, auth);
        }
        return update.then(
                        audit(occupations.createUpdate(), auth, row.getVersion())
                                .set("state", "RELEASED")
                                .set("releaseReason", reason)
                                .set("releasedAt", System.currentTimeMillis())
                                .where("id", row.getId())
                                .and("projectId", row.getProjectId())
                                .and("version", row.getVersion())
                                .and("state", "ACTIVE")
                                .and("deleted", false)
                                .execute()
                                .flatMap(SubjectService::changed))
                .then(occupations.findById(row.getId()));
    }

    private Mono<Void> billCounter(ReceivableEntity bill, BigDecimal delta, Authentication auth) {
        var next = money(bill.getInvoiceOccupiedAmount()).add(delta);
        if (next.signum() < 0) return Mono.error(error("document_occupation_conflict", 409));
        return audit(bills.createUpdate(), auth, bill.getVersion())
                .set("invoiceOccupiedAmount", next)
                .where("id", bill.getId())
                .and("projectId", bill.getProjectId())
                .and("version", bill.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    /** 来源和账户已持锁。多笔来源可共用账户，逐次读取当前版本，不能用各来源加载时的旧计数覆盖。 */
    private Mono<Void> fundCounter(
            DocumentSourceService.Source source, BigDecimal delta, Authentication auth) {
        var fund = source.fund();
        return fundAccounts
                .findById(fund.account().getId())
                .switchIfEmpty(Mono.error(error("document_occupation_conflict", 409)))
                .flatMap(
                        account ->
                                fundCredits
                                        .findById(fund.credit().getId())
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error("document_occupation_conflict", 409)))
                                        .flatMap(
                                                credit -> {
                                                    var occupied =
                                                            money(credit.getInvoiceOccupiedAmount())
                                                                    .add(delta);
                                                    var total =
                                                            money(
                                                                            account
                                                                                    .getInvoiceOccupiedAmount())
                                                                    .add(delta);
                                                    if (!source.projectId()
                                                                    .equals(account.getProjectId())
                                                            || !source.projectId()
                                                                    .equals(credit.getProjectId())
                                                            || !account.getId()
                                                                    .equals(credit.getAccountId())
                                                            || Boolean.TRUE.equals(
                                                                    account.getDeleted())
                                                            || Boolean.TRUE.equals(
                                                                    credit.getDeleted())
                                                            || occupied.signum() < 0
                                                            || occupied.compareTo(
                                                                            money(
                                                                                    credit
                                                                                            .getRemainingAmount()))
                                                                    > 0
                                                            || total.signum() < 0
                                                            || total.compareTo(
                                                                            money(
                                                                                    account
                                                                                            .getBalance()))
                                                                    > 0)
                                                        return Mono.error(
                                                                error(
                                                                        "document_occupation_conflict",
                                                                        409));
                                                    return audit(
                                                                    fundCredits.createUpdate(),
                                                                    auth,
                                                                    credit.getVersion())
                                                            .set("invoiceOccupiedAmount", occupied)
                                                            .where("id", credit.getId())
                                                            .and("projectId", source.projectId())
                                                            .and("version", credit.getVersion())
                                                            .and("deleted", false)
                                                            .execute()
                                                            .flatMap(SubjectService::changed)
                                                            .then(
                                                                    audit(
                                                                                    fundAccounts
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    account
                                                                                            .getVersion())
                                                                            .set(
                                                                                    "invoiceOccupiedAmount",
                                                                                    total)
                                                                            .where(
                                                                                    "id",
                                                                                    account.getId())
                                                                            .and(
                                                                                    "projectId",
                                                                                    source
                                                                                            .projectId())
                                                                            .and(
                                                                                    "version",
                                                                                    account
                                                                                            .getVersion())
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    SubjectService
                                                                                            ::changed))
                                                            .then();
                                                }));
    }

    private Mono<Void> paymentCounters(
            DocumentSourceService.Source source,
            Map<String, String> allocation,
            boolean release,
            Authentication auth) {
        var indexed = new HashMap<String, PaymentAllocationEntity>();
        for (var line : source.paymentLines()) indexed.put(line.getId(), line);
        for (var entry : allocation.entrySet()) {
            var line = indexed.get(entry.getKey());
            if (line == null) throw error("document_occupation_conflict", 409);
            var amount = rules.amount(entry.getValue(), false);
            var current = money(line.getInvoiceOccupiedAmount());
            var next = release ? current.subtract(amount) : current.add(amount);
            if (next.signum() < 0
                    || next.compareTo(
                                    money(line.getAmount())
                                            .subtract(money(line.getRefundedAmount())))
                            > 0) throw error("document_occupation_conflict", 409);
        }
        return Flux.fromIterable(new TreeMap<>(allocation).entrySet())
                .concatMap(
                        entry -> {
                            var line = indexed.get(entry.getKey());
                            var delta = rules.amount(entry.getValue(), false);
                            var next =
                                    release
                                            ? line.getInvoiceOccupiedAmount().subtract(delta)
                                            : line.getInvoiceOccupiedAmount().add(delta);
                            return audit(lines.createUpdate(), auth, line.getVersion())
                                    .set("invoiceOccupiedAmount", next)
                                    .where("id", line.getId())
                                    .and("projectId", source.projectId())
                                    .and("version", line.getVersion())
                                    .and("deleted", false)
                                    .execute()
                                    .flatMap(SubjectService::changed);
                        })
                .then();
    }

    private Mono<List<DocumentOccupationEntity>> rows(String project, String document) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.setPageSize(1001);
        query.setPaging(true);
        query.orderBy("id");
        return occupations
                .createQuery()
                .setParam(query)
                .where("documentId", document)
                .and("deleted", false)
                .fetch()
                .collectList()
                .map(
                        rows -> {
                            if (rows.size() > 1000
                                    || rows.stream()
                                            .anyMatch(row -> !project.equals(row.getProjectId())))
                                throw error("document_occupation_conflict", 409);
                            return rows;
                        });
    }

    private void validate(
            String document,
            String type,
            List<Claim> claims,
            List<DocumentSourceService.Source> sources) {
        if (!id(document)
                || !Set.of("INVOICE", "RECEIPT").contains(Objects.toString(type, ""))
                || claims == null
                || claims.isEmpty()
                || claims.size() > 1000
                || claims.stream().anyMatch(Objects::isNull))
            throw error("document_source_invalid", 400);
        rules.assertDistinct(claims.stream().map(Claim::source).toList());
        var indexed = index(sources);
        if (indexed.size() != claims.size()
                || claims.stream().anyMatch(claim -> !indexed.containsKey(claim.source()))
                || sources.stream().map(source -> source.tax().companyId()).distinct().count() != 1)
            throw error("document_occupation_conflict", 409);
        for (var claim : claims) rules.amount(money(claim.amount()).toPlainString(), false);
    }

    private void replay(
            List<DocumentOccupationEntity> existing,
            String type,
            List<Claim> claims,
            List<DocumentSourceService.Source> sources) {
        var indexed = index(sources);
        var amounts = new HashMap<CanonicalSource, BigDecimal>();
        for (var claim : claims) amounts.put(claim.source(), money(claim.amount()));
        if (existing.size() != claims.size()) throw error("document_occupation_conflict", 409);
        for (var row : existing) {
            assertSource(row, indexed.get(key(row)));
            if (!"ACTIVE".equals(row.getState())
                    || !type.equals(row.getDocumentType())
                    || amounts.get(key(row)) == null
                    || row.getAmount().compareTo(amounts.get(key(row))) != 0)
                throw error("document_occupation_conflict", 409);
        }
    }

    private Map<CanonicalSource, DocumentSourceService.Source> index(
            List<DocumentSourceService.Source> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 1000)
            throw error("document_source_invalid", 400);
        rules.assertDistinct(
                sources.stream().map(DocumentSourceService.Source::canonical).toList());
        var result = new HashMap<CanonicalSource, DocumentSourceService.Source>();
        for (var source : sources) result.put(source.canonical(), source);
        return result;
    }

    private static void assertSource(
            DocumentOccupationEntity row, DocumentSourceService.Source source) {
        if (source == null
                || !row.getProjectId().equals(source.projectId())
                || !row.getCompanyId().equals(source.tax().companyId())
                || !row.getSpaceId().equals(source.spaceId()))
            throw error("document_occupation_conflict", 409);
    }

    private static CanonicalSource key(DocumentOccupationEntity row) {
        return new CanonicalSource(row.getSourceType(), row.getSourceId());
    }

    private BigDecimal money(BigDecimal value) {
        return rules.amount(value == null ? null : value.toPlainString(), true);
    }

    private static boolean id(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.trim());
    }

    private static Map<String, String> allocation(String json) {
        try {
            var parsed = JSON.parseObject(json);
            if (parsed == null || parsed.isEmpty() || parsed.size() > 1000)
                throw error("document_occupation_conflict", 409);
            var result = new TreeMap<String, String>();
            for (var key : parsed.keySet()) result.put(key, parsed.getString(key));
            return result;
        } catch (RuntimeException invalid) {
            throw error("document_occupation_conflict", 409);
        }
    }

    record Claim(CanonicalSource source, BigDecimal amount) {}
}
