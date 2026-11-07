package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 财务月结的只读来源核对，不关闭计费账期、不变更资金或交账。 期间必须由调用方从服务端会计账期目录取得；allReviewed仅表示来源均已审核，不能代替月结资格。
 * 确认方仍须持久门闩、重新加载并校验；本组件无缓存和后台任务。
 */
@Component
@RequiredArgsConstructor
public class MonthClosingPreparation {
    private static final int MAX_SOURCES = 10000;
    private final FinanceScope scope;
    private final HandoverSources sources;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<HandoverSourceEntity, String> bindings;
    private final ReactiveRepository<HandoverEntity, String> handovers;
    private final ReactiveRepository<HandoverLineEntity, String> handoverLines;

    public record Period(String month, LocalDate start, LocalDate end) {}

    public record PreparedSource(
            String type,
            String id,
            Long version,
            String amount,
            String cashAmount,
            String handoverId,
            Long handoverVersion,
            String reviewState,
            boolean monthClosed) {}

    public record Result(
            String token,
            List<PreparedSource> sources,
            String amount,
            String cashAmount,
            int unhandedCount,
            int pendingReviewCount,
            boolean allReviewed) {}

    private record Fact(
            String type,
            String id,
            Long version,
            BigDecimal amount,
            BigDecimal cash,
            String lineFingerprint) {}

    /** 不做分页后过滤：任一来源的任一房屋无保存权限，拒绝整个期间，避免将部分可见误判为全部可结。 */
    public Mono<Result> prepare(String project, Period period) {
        return scope.project(project, CrudAssetPermission.save)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                Mono.defer(
                                        () -> {
                                            validate(period);
                                            return candidates(project, period)
                                                    .flatMap(
                                                            items ->
                                                                    prepare(
                                                                            project, tenant, period,
                                                                            items));
                                        }));
    }

    private Mono<Result> prepare(
            String project, String tenant, Period period, List<HandoverSources.Item> items) {
        if (items.size() > MAX_SOURCES) return Mono.error(error("month_closing_source_limit", 409));
        return Mono.zip(facts(project, items), claims(project, tenant, items))
                .flatMap(
                        read -> prepareReview(project, tenant, period, read.getT1(), read.getT2()));
    }

    private Mono<Result> prepareReview(
            String project,
            String tenant,
            Period period,
            List<Fact> facts,
            Map<String, HandoverSourceEntity> claims) {
        return batches(project, tenant, claims.values())
                .flatMap(
                        batches ->
                                verifySnapshots(project, facts, claims)
                                        .then(
                                                Mono.fromSupplier(
                                                        () ->
                                                                assemble(
                                                                        project, tenant, period,
                                                                        facts, claims, batches))));
    }

    private Mono<List<Fact>> facts(String project, List<HandoverSources.Item> items) {
        // 每笔完成整单授权后只保留汇总和明细摘要，避免整期所有房屋明细同时常驻内存。
        return Flux.fromIterable(items)
                .concatMap(item -> sources.load(project, item, false, CrudAssetPermission.save))
                .map(
                        source ->
                                new Fact(
                                        source.type(),
                                        source.id(),
                                        source.version(),
                                        HandoverSources.sum(source.lines(), false),
                                        HandoverSources.sum(source.lines(), true),
                                        lineFingerprint(source.lines())))
                .collectList();
    }

    private Mono<Void> verifySnapshots(
            String project, List<Fact> facts, Map<String, HandoverSourceEntity> claims) {
        return Flux.fromIterable(facts)
                .concatMap(
                        fact -> {
                            var claim = claims.get(key(fact.type(), fact.id()));
                            if (claim == null) return Mono.empty();
                            return handoverLines
                                    .createQuery()
                                    .setParam(HandoverSources.limit())
                                    .where("projectId", project)
                                    .and("batchId", claim.getBatchId())
                                    .and("sourceType", fact.type())
                                    .and("sourceId", fact.id())
                                    .and("deleted", false)
                                    .fetch()
                                    .collectList()
                                    .flatMap(
                                            lines -> {
                                                // 活动绑定不能借另一批次的审核状态过关；冻结业务事实须与完整原来源一致。
                                                if (lines.isEmpty()
                                                        || lines.size() > MAX_SOURCES
                                                        || !fact.lineFingerprint()
                                                                .equals(lineFingerprint(lines)))
                                                    return Mono.error(
                                                            error(
                                                                    "month_closing_source_invalid",
                                                                    409));
                                                return sources.authorize(
                                                        lines, CrudAssetPermission.save);
                                            });
                        })
                .then();
    }

    private static String lineFingerprint(List<HandoverLineEntity> lines) {
        var rows =
                lines.stream()
                        .sorted(
                                Comparator.comparing(
                                        HandoverLineEntity::getOriginalLineId,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                        .map(
                                l ->
                                        Arrays.asList(
                                                l.getSourceType(),
                                                l.getSourceId(),
                                                l.getOriginalLineId(),
                                                l.getReceivableId(),
                                                l.getSpaceId(),
                                                l.getFullName(),
                                                l.getCustomerName(),
                                                l.getCompanyId(),
                                                l.getCompanyName(),
                                                l.getSubjectName(),
                                                l.getMethodText(),
                                                l.getBankName(),
                                                l.getOccurredAt(),
                                                l.getAmount(),
                                                l.getCashAmount()))
                        .toList();
        return PaymentRules.digest(JSON.toJSONString(rows));
    }

    private Mono<List<HandoverSources.Item>> candidates(String project, Period period) {
        var paid =
                payments.createQuery()
                        .setParam(range(project, "paidAt", period))
                        .and("status", "SUCCEEDED")
                        .fetch()
                        .map(p -> new HandoverSources.Item("PAYMENT", p.getId(), p.getVersion()));
        var returned =
                refunds.createQuery()
                        .setParam(range(project, "refundedAt", period))
                        .and("status", "SUCCEEDED")
                        .fetch()
                        .map(p -> new HandoverSources.Item("REFUND", p.getId(), p.getVersion()));
        var funds =
                entries.createQuery()
                        .setParam(range(project, "occurredAt", period))
                        .isNull("paymentId")
                        .isNull("refundId")
                        .fetch()
                        .map(p -> new HandoverSources.Item("FUND", p.getId(), p.getVersion()));
        return Flux.concat(paid, returned, funds)
                .take(MAX_SOURCES + 1L)
                .sort(
                        Comparator.comparing(HandoverSources.Item::type)
                                .thenComparing(HandoverSources.Item::id))
                .collectList();
    }

    private Mono<Map<String, HandoverSourceEntity>> claims(
            String project, String tenant, List<HandoverSources.Item> items) {
        if (items.isEmpty()) return Mono.just(Map.of());
        var wanted = new HashSet<String>();
        items.forEach(i -> wanted.add(key(i.type(), i.id())));
        return bindings.createQuery()
                .setParam(unpaged())
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .notNull("batchId")
                .in("sourceId", items.stream().map(HandoverSources.Item::id).distinct().toList())
                .fetch()
                .collectList()
                .map(
                        rows -> {
                            var result = new HashMap<String, HandoverSourceEntity>();
                            for (var row : rows) {
                                var key = key(row.getSourceType(), row.getSourceId());
                                if (wanted.contains(key) && result.put(key, row) != null)
                                    throw error("month_closing_source_invalid", 409);
                            }
                            return result;
                        });
    }

    private Mono<Map<String, HandoverEntity>> batches(
            String project, String tenant, Collection<HandoverSourceEntity> claims) {
        if (claims.isEmpty()) return Mono.just(Map.of());
        var ids = claims.stream().map(HandoverSourceEntity::getBatchId).distinct().toList();
        return handovers
                .createQuery()
                .setParam(unpaged())
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .in("id", ids)
                .fetch()
                .collectMap(HandoverEntity::getId)
                .map(
                        rows -> {
                            if (rows.size() != ids.size())
                                throw error("month_closing_source_invalid", 409);
                            return rows;
                        });
    }

    private Result assemble(
            String project,
            String tenant,
            Period period,
            List<Fact> facts,
            Map<String, HandoverSourceEntity> claims,
            Map<String, HandoverEntity> batches) {
        var prepared = new ArrayList<PreparedSource>();
        var fingerprints = new ArrayList<Object>();
        var amount = BigDecimal.ZERO.setScale(2);
        var cash = amount;
        int unhanded = 0, pending = 0;
        for (var source : facts) {
            var claim = claims.get(key(source.type(), source.id()));
            var batch = claim == null ? null : batches.get(claim.getBatchId());
            if (batch != null && !Set.of("PENDING", "APPROVED").contains(batch.getStatus()))
                throw error("month_closing_source_invalid", 409);
            var state =
                    batch == null
                            ? "NOT_HANDED"
                            : "APPROVED".equals(batch.getStatus()) ? "APPROVED" : "PENDING_REVIEW";
            if (batch == null) unhanded++;
            else if ("PENDING".equals(batch.getStatus())) pending++;
            var value = source.amount();
            var cashValue = source.cash();
            amount = amount.add(value);
            cash = cash.add(cashValue);
            var row =
                    new PreparedSource(
                            source.type(),
                            source.id(),
                            source.version(),
                            value.toPlainString(),
                            cashValue.toPlainString(),
                            batch == null ? null : batch.getId(),
                            batch == null ? null : batch.getVersion(),
                            state,
                            batch != null && Boolean.TRUE.equals(batch.getMonthClosed()));
            prepared.add(row);
            // 不依赖record自动序列化；绑定/审核版本和完整原始行变化均使确认令牌失效。
            fingerprints.add(
                    Arrays.asList(
                            row.type(),
                            row.id(),
                            row.version(),
                            row.amount(),
                            row.cashAmount(),
                            row.handoverId(),
                            row.handoverVersion(),
                            row.reviewState(),
                            row.monthClosed(),
                            claim == null ? null : claim.getVersion(),
                            source.lineFingerprint()));
        }
        if (amount.precision() > 20 || cash.precision() > 20)
            throw error("month_closing_source_limit", 409);
        var token =
                PaymentRules.digest(
                        JSON.toJSONString(
                                List.of(
                                        project,
                                        tenant,
                                        period.month(),
                                        period.start().toString(),
                                        period.end().toString(),
                                        fingerprints)));
        return new Result(
                token,
                List.copyOf(prepared),
                amount.toPlainString(),
                cash.toPlainString(),
                unhanded,
                pending,
                unhanded == 0 && pending == 0);
    }

    private static QueryParamEntity range(String project, String dateField, Period period) {
        var q = new QueryParamEntity();
        q.setPageSize(MAX_SOURCES + 1);
        q.orderBy("id");
        // 左闭右开覆盖期末带小数秒的流水；不使用字符串日期前缀猜测自定义账期。
        q.toQuery()
                .and("projectId", project)
                .and("deleted", false)
                .gte(dateField, period.start() + "T00:00")
                .lt(dateField, period.end().plusDays(1) + "T00:00");
        return q;
    }

    private static QueryParamEntity unpaged() {
        var q = new QueryParamEntity();
        q.setPaging(false);
        return q;
    }

    private static String key(String type, String id) {
        return type + ":" + id;
    }

    private static void validate(Period period) {
        try {
            if (period == null
                    || period.month() == null
                    || !period.month().matches("[0-9]{4}-[0-9]{2}")
                    || YearMonth.parse(period.month()).getYear() < 1
                    || period.start() == null
                    || period.end() == null
                    || period.start().isAfter(period.end())
                    || period.end().getYear() > 9998
                    || period.start().getYear() < 1)
                throw error("month_closing_period_invalid", 400);
        } catch (java.time.DateTimeException failure) {
            throw error("month_closing_period_invalid", 400);
        }
    }
}
