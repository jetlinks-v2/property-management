package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 资金业务唯一入口，预览不写库；余额、内部核销、双边流水在同一响应式事务内发布。 */
@Service
@RequiredArgsConstructor
public class FundsService {
    private final FinanceScope scope;
    private final FundSourceService sources;
    private final CashierConfiguration configuration;
    private final FundLedger ledger;
    private final FundOffsetService offsets;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final ReactiveRepository<FundEntryEntity, String> entries;

    public Mono<String> quote(
            String project, String standard, String company, BigDecimal quantity) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    if (quantity == null
                                            || quantity.signum() <= 0
                                            || quantity.scale() > 4
                                            || quantity.precision() > 12)
                                        return Mono.error(error("fund_invalid", 400));
                                    return sources.standard(project, standard, company, false)
                                            .map(
                                                    s ->
                                                            FundRules.money(
                                                                            s.getPrice()
                                                                                    .multiply(
                                                                                            quantity)
                                                                                    .setScale(
                                                                                            s
                                                                                                    .getChargeRoundType(),
                                                                                            org
                                                                                                    .jetlinks
                                                                                                    .pro
                                                                                                    .property
                                                                                                    .finance
                                                                                                    .setting
                                                                                                    .SettingRules
                                                                                                    .rounding(
                                                                                                            s)))
                                                                    .toPlainString());
                                }));
    }

    public Mono<Preview> preview(String project, FundsInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    FundRules.validate(input, date);
                                    return sources.prepare(project, input, false)
                                            .flatMap(
                                                    source ->
                                                            config(project, input, false)
                                                                    .flatMap(
                                                                            config ->
                                                                                    offsets.prepare(
                                                                                                    project,
                                                                                                    source
                                                                                                            .source(),
                                                                                                    input,
                                                                                                    date,
                                                                                                    false)
                                                                                            .map(
                                                                                                    Optional
                                                                                                            ::of)
                                                                                            .defaultIfEmpty(
                                                                                                    Optional
                                                                                                            .empty())
                                                                                            .map(
                                                                                                    offset ->
                                                                                                            prepare(
                                                                                                                    input,
                                                                                                                    date,
                                                                                                                    source,
                                                                                                                    config,
                                                                                                                    offset
                                                                                                                            .orElse(
                                                                                                                                    null)))));
                                }));
    }

    @Transactional
    public Mono<Result> execute(String project, Submission request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> executeAuthorized(project, request, date, auth)))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/funds/execute"));
    }

    private Mono<Result> executeAuthorized(
            String project, Submission request, LocalDate date, Authentication auth) {
        if (request == null
                || request.requestId() == null
                || !request.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || request.token() == null
                || !request.token().matches("[a-f0-9]{64}"))
            return Mono.error(error("fund_invalid", 400));
        FundRules.validate(request.input(), date);
        var fingerprint = FundRules.fingerprint(request.input());
        var id =
                UUID.nameUUIDFromBytes(
                                ("fund:" + project.length() + ":" + project + request.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        return existing(project, id, fingerprint)
                .switchIfEmpty(
                        Mono.defer(() -> commit(project, id, fingerprint, request, date, auth)));
    }

    private Mono<Result> commit(
            String project,
            String id,
            String fingerprint,
            Submission request,
            LocalDate date,
            Authentication auth) {
        // 先锁基础档案，再锁公司、科目/标准、应收、资金账户，和柜台/标准维护采用相同锁序。
        return sources.prepare(project, request.input(), true)
                .then(config(project, request.input(), true))
                .flatMap(
                        config ->
                                sources.lockReferences(project, request.input())
                                        .then(sources.prepare(project, request.input(), true))
                                        .flatMap(
                                                source ->
                                                        prepareOffset(
                                                                        project,
                                                                        source,
                                                                        request.input(),
                                                                        date)
                                                                .flatMap(
                                                                        offset ->
                                                                                sources.lock(
                                                                                                project,
                                                                                                source)
                                                                                        .flatMap(
                                                                                                locked ->
                                                                                                        existing(
                                                                                                                        project,
                                                                                                                        id,
                                                                                                                        fingerprint)
                                                                                                                .switchIfEmpty(
                                                                                                                        Mono
                                                                                                                                .defer(
                                                                                                                                        () ->
                                                                                                                                                write(
                                                                                                                                                        id,
                                                                                                                                                        fingerprint,
                                                                                                                                                        request,
                                                                                                                                                        date,
                                                                                                                                                        locked,
                                                                                                                                                        config,
                                                                                                                                                        offset
                                                                                                                                                                .orElse(
                                                                                                                                                                        null),
                                                                                                                                                        auth)))))));
    }

    private Mono<Optional<FundOffsetService.Prepared>> prepareOffset(
            String project, FundSourceService.Prepared source, FundsInput input, LocalDate date) {
        return offsets.prepare(project, source.source(), input, date, true)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<CashierConfiguration.Resolved> config(String project, FundsInput i, boolean lock) {
        if ("TOPUP".equals(i.getAction()) || "REFUND".equals(i.getAction())) {
            var cash = new CashierInput();
            cash.setCompanyId(i.getCompanyId());
            cash.setTenders(
                    List.of(
                            new CashierInput.Tender(
                                    i.getMethod(), i.getRecordedId(), i.getAmount())));
            return configuration.resolve(project, cash, lock);
        }
        return configuration
                .balanceCompany(project, i.getCompanyId(), lock)
                .map(c -> new CashierConfiguration.Resolved(c, List.of()));
    }

    private Preview prepare(
            FundsInput i,
            LocalDate date,
            FundSourceService.Prepared p,
            CashierConfiguration.Resolved config,
            FundOffsetService.Prepared offset) {
        var a = p.source();
        if (!"TOPUP".equals(i.getAction())) {
            if (!Objects.equals(a.getVersion(), i.getVersion()))
                throw error("version_conflict", 409);
            if (a.getBalance().compareTo(i.getAmount()) < 0) throw error("fund_insufficient", 409);
            if (a.getInvoiceOccupiedAmount().signum() > 0)
                throw error("fund_invoice_occupied", 409);
        }
        var amount = FundRules.money(i.getAmount());
        var signed = "TOPUP".equals(i.getAction()) ? amount : amount.negate();
        if (a.getBalance().add(signed).precision() - a.getBalance().add(signed).scale() > 18)
            throw error("fund_invalid", 400);
        if (p.target() != null
                && p.target().getBalance().add(amount).precision()
                                - p.target().getBalance().add(amount).scale()
                        > 18) throw error("fund_invalid", 400);
        String method = i.getMethod(), text, bank = null;
        if (config.funding().isEmpty()) {
            method = a.getType();
            text =
                    "TRANSFER".equals(i.getAction())
                            ? "资金划转"
                            : "DEDUCT".equals(i.getAction())
                                    ? "押金扣除"
                                    : a.getType().equals("ADVANCE") ? "预收款结转" : "押金结转";
        } else {
            text = config.funding().get(0).text();
            bank = config.funding().get(0).bankName();
        }
        var identity =
                com.alibaba.fastjson.JSON.toJSONString(
                        List.of(
                                FundRules.fingerprint(i),
                                date.toString(),
                                a.getId(),
                                a.getVersion(),
                                a.getBalance(),
                                Objects.toString(a.getSourceIdentity(), ""),
                                config.identity(),
                                p.referenceIdentity(),
                                Objects.toString(a.getDocumentSnapshotJson(), ""),
                                p.target() == null
                                        ? ""
                                        : p.target().getId()
                                                + ":"
                                                + p.target().getVersion()
                                                + ":"
                                                + p.target().getBalance()
                                                + ":"
                                                + Objects.toString(
                                                        p.target().getSourceIdentity(), ""),
                                offset == null ? "" : offset.result().token()));
        return new Preview(
                PaymentRules.digest(identity),
                amount.toPlainString(),
                a.getBalance().toPlainString(),
                a.getBalance().add(signed).toPlainString(),
                p.target() == null ? null : p.target().getBalance().add(amount).toPlainString(),
                config.company().getCompanyName(),
                a.getFullName(),
                a.getCustomerName(),
                a.getSubjectName(),
                p.target() == null ? null : p.target().getFullName(),
                p.target() == null ? null : p.target().getCustomerName(),
                p.target() == null ? null : p.target().getStandardName(),
                method,
                text,
                bank,
                offset == null
                        ? List.of()
                        : offset.result().rows().stream()
                                .map(
                                        row ->
                                                org.jetlinks.pro.property.finance.receivable
                                                        .ReceivableView.of(row, false))
                                .toList());
    }

    private Mono<Result> write(
            String id,
            String fingerprint,
            Submission request,
            LocalDate date,
            FundSourceService.Prepared p,
            CashierConfiguration.Resolved config,
            FundOffsetService.Prepared offset,
            Authentication auth) {
        var i = request.input();
        var preview = prepare(i, date, p, config, offset);
        if (!preview.token().equals(request.token()))
            return Mono.error(error("fund_preview_expired", 409));
        var a = p.source();
        a.setCompanyName(config.company().getCompanyName());
        if (p.target() != null) p.target().setCompanyName(a.getCompanyName());
        var signed = "TOPUP".equals(i.getAction()) ? i.getAmount() : i.getAmount().negate();
        var cash =
                Set.of("TOPUP", "REFUND").contains(i.getAction())
                        ? signed
                        : BigDecimal.ZERO.setScale(2);
        return offsets.persist(id, fingerprint, a, i, offset, config.company(), auth)
                .then(
                        ledger.apply(
                                id,
                                a,
                                p.target(),
                                i,
                                signed,
                                cash,
                                preview.method(),
                                preview.methodText(),
                                preview.bankName(),
                                offset == null ? null : id,
                                fingerprint,
                                auth))
                .flatMap(
                        entry -> {
                            if (p.target() == null) return Mono.just(new Result(a, null, entry));
                            return ledger.apply(
                                            id,
                                            p.target(),
                                            a,
                                            i,
                                            i.getAmount(),
                                            BigDecimal.ZERO.setScale(2),
                                            preview.method(),
                                            preview.methodText(),
                                            null,
                                            null,
                                            fingerprint,
                                            auth)
                                    .thenReturn(new Result(a, p.target(), entry));
                        });
    }

    private Mono<Result> existing(String project, String id, String fingerprint) {
        return entries.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .flatMap(
                        entry -> {
                            if (!fingerprint.equals(entry.getRequestFingerprint()))
                                return Mono.error(error("fund_request_conflict", 409));
                            return sources.account(project, entry.getAccountId(), false)
                                    .flatMap(
                                            a -> {
                                                if (entry.getPeerAccountId() == null)
                                                    return Mono.just(new Result(a, null, entry));
                                                return sources.account(
                                                                project,
                                                                entry.getPeerAccountId(),
                                                                false)
                                                        .map(
                                                                target ->
                                                                        new Result(
                                                                                a, target, entry));
                                            });
                        });
    }

    public Mono<PagerResult<FundAccountEntity>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = SubjectService.page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("deleted", false));
                                    return AssetsHolder.injectQueryParam(
                                                    q, AssetType.of("space"), "spaceId")
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.queryPager(
                                                                    secured,
                                                                    accounts::createQuery));
                                }));
    }

    public Mono<FundAccountEntity> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        accounts.createQuery()
                                .where("id", id)
                                .and("projectId", project)
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("fund_not_found", 404))))
                .flatMap(
                        a ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                a.getSpaceId(),
                                                CrudAssetPermission.read)
                                        .thenReturn(a));
    }

    public Mono<PagerResult<FundEntryEntity>> history(
            String project, String account, QueryParamEntity input) {
        return detail(project, account)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = SubjectService.page(input);
                                    q.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("accountId", account)
                                                            .and("deleted", false));
                                    return QueryHelper.queryPager(q, entries::createQuery);
                                }));
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("fund_request_conflict", 409);
        return failure;
    }

    public record Submission(String requestId, String token, FundsInput input) {}

    public record Result(
            FundAccountEntity account, FundAccountEntity target, FundEntryEntity entry) {}

    public record Preview(
            String token,
            String amount,
            String beforeBalance,
            String afterBalance,
            String targetBalance,
            String companyName,
            String fullName,
            String customerName,
            String subjectName,
            String targetFullName,
            String targetCustomerName,
            String targetStandardName,
            String method,
            String methodText,
            String bankName,
            List<org.jetlinks.pro.property.finance.receivable.ReceivableView> bills) {}
}
