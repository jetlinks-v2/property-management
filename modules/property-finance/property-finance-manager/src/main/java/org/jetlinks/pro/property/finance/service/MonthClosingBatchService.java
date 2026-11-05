package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.AccountingPeriodEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 预检完整选择集；各期独立事务，依赖期失败后明确标记后续未执行。 */
@Service
@RequiredArgsConstructor
public class MonthClosingBatchService {
    private final MonthClosingService service;
    private final ReactiveRepository<AccountingPeriodEntity, String> periods;

    public record Item(String month, Long version, String requestId) {}

    public record Input(List<Item> items, String reason) {}

    public record Row(
            String month, String status, String errorCode, MonthClosingService.Preview preview) {}

    public Mono<List<Row>> preview(String project, String action, Input input, LocalDate today) {
        var items = ordered(action, input);
        return TenantHolder.currentId()
                .defaultIfEmpty("")
                .flatMap(
                        tenant ->
                                periods.createQuery()
                                        .setParam(HandoverSources.limit())
                                        .where("projectId", project)
                                        .and("tenantId", tenant)
                                        .and("deleted", false)
                                        .fetch()
                                        .collectList()
                                        .flatMap(
                                                all -> {
                                                    var chosen = new HashSet<String>();
                                                    items.forEach(item -> chosen.add(item.month()));
                                                    return Flux.fromIterable(items)
                                                            .concatMap(
                                                                    item -> {
                                                                        boolean dependency =
                                                                                all.stream()
                                                                                        .anyMatch(
                                                                                                p ->
                                                                                                        !chosen
                                                                                                                        .contains(
                                                                                                                                p
                                                                                                                                        .getAccountMonth())
                                                                                                                && ("CLOSE"
                                                                                                                                .equals(
                                                                                                                                        action)
                                                                                                                        ? p.getAccountMonth()
                                                                                                                                                .compareTo(
                                                                                                                                                        item
                                                                                                                                                                .month())
                                                                                                                                        < 0
                                                                                                                                && !"CLOSED"
                                                                                                                                        .equals(
                                                                                                                                                p
                                                                                                                                                        .getStatus())
                                                                                                                        : p.getAccountMonth()
                                                                                                                                                .compareTo(
                                                                                                                                                        item
                                                                                                                                                                .month())
                                                                                                                                        > 0
                                                                                                                                && "CLOSED"
                                                                                                                                        .equals(
                                                                                                                                                p
                                                                                                                                                        .getStatus())));
                                                                        return service.batchReplay(
                                                                                        project,
                                                                                        item
                                                                                                .month(),
                                                                                        action,
                                                                                        item
                                                                                                .requestId(),
                                                                                        item
                                                                                                .version(),
                                                                                        input
                                                                                                .reason())
                                                                                .map(
                                                                                        saved ->
                                                                                                new Row(
                                                                                                        item
                                                                                                                .month(),
                                                                                                        "COMPLETED",
                                                                                                        null,
                                                                                                        null))
                                                                                .switchIfEmpty(
                                                                                        Mono.defer(
                                                                                                () -> {
                                                                                                    if (dependency)
                                                                                                        return Mono
                                                                                                                .just(
                                                                                                                        new Row(
                                                                                                                                item
                                                                                                                                        .month(),
                                                                                                                                "FAILED",
                                                                                                                                "error.property_finance_month_closing_sequence",
                                                                                                                                null));
                                                                                                    return service.batchPreview(
                                                                                                                    project,
                                                                                                                    item
                                                                                                                            .month(),
                                                                                                                    action,
                                                                                                                    today)
                                                                                                            .map(
                                                                                                                    quote -> {
                                                                                                                        boolean
                                                                                                                                blocked =
                                                                                                                                        !Objects
                                                                                                                                                        .equals(
                                                                                                                                                                quote
                                                                                                                                                                        .version(),
                                                                                                                                                                item
                                                                                                                                                                        .version())
                                                                                                                                                || quote
                                                                                                                                                                .unhandedCount()
                                                                                                                                                        > 0
                                                                                                                                                || quote
                                                                                                                                                                .pendingHandoverCount()
                                                                                                                                                        > 0
                                                                                                                                                || quote
                                                                                                                                                                .requireReceivableReview()
                                                                                                                                                        && quote
                                                                                                                                                                        .pendingReceivableCount()
                                                                                                                                                                > 0;
                                                                                                                        return new Row(
                                                                                                                                item
                                                                                                                                        .month(),
                                                                                                                                blocked
                                                                                                                                        ? "FAILED"
                                                                                                                                        : "READY",
                                                                                                                                blocked
                                                                                                                                        ? "error.property_finance_month_closing_source_invalid"
                                                                                                                                        : null,
                                                                                                                                quote);
                                                                                                                    });
                                                                                                }))
                                                                                .onErrorResume(
                                                                                        error ->
                                                                                                Mono
                                                                                                        .just(
                                                                                                                new Row(
                                                                                                                        item
                                                                                                                                .month(),
                                                                                                                        "FAILED",
                                                                                                                        error
                                                                                                                                .getMessage(),
                                                                                                                        null)));
                                                                    })
                                                            .collectList();
                                                }));
    }

    public Mono<List<Row>> execute(String project, String action, Input input, LocalDate today) {
        var items = ordered(action, input);
        return preview(project, action, input, today)
                .flatMap(
                        checked -> {
                            if (checked.stream().anyMatch(row -> "FAILED".equals(row.status())))
                                return Mono.just(
                                        checked.stream()
                                                .map(
                                                        row ->
                                                                "READY".equals(row.status())
                                                                        ? new Row(
                                                                                row.month(),
                                                                                "NOT_EXECUTED",
                                                                                null,
                                                                                row.preview())
                                                                        : row)
                                                .toList());
                            var stopped = new boolean[1];
                            return Flux.fromIterable(items)
                                    .concatMap(
                                            item -> {
                                                if (stopped[0])
                                                    return Mono.just(
                                                            new Row(
                                                                    item.month(),
                                                                    "NOT_EXECUTED",
                                                                    null,
                                                                    null));
                                                return service.batchReplay(
                                                                project,
                                                                item.month(),
                                                                action,
                                                                item.requestId(),
                                                                item.version(),
                                                                input.reason())
                                                        .map(
                                                                saved ->
                                                                        new Row(
                                                                                item.month(),
                                                                                "COMPLETED",
                                                                                null,
                                                                                null))
                                                        .switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                service.preview(
                                                                                                project,
                                                                                                item
                                                                                                        .month(),
                                                                                                action,
                                                                                                today)
                                                                                        .flatMap(
                                                                                                quote ->
                                                                                                        service
                                                                                                                .submit(
                                                                                                                        project,
                                                                                                                        item
                                                                                                                                .month(),
                                                                                                                        action,
                                                                                                                        new MonthClosingService
                                                                                                                                .Submission(
                                                                                                                                item
                                                                                                                                        .requestId(),
                                                                                                                                item
                                                                                                                                        .version(),
                                                                                                                                quote
                                                                                                                                        .token(),
                                                                                                                                input
                                                                                                                                        .reason()),
                                                                                                                        today))
                                                                                        .map(
                                                                                                saved ->
                                                                                                        new Row(
                                                                                                                item
                                                                                                                        .month(),
                                                                                                                "COMPLETED",
                                                                                                                null,
                                                                                                                null))))
                                                        .onErrorResume(
                                                                error -> {
                                                                    stopped[0] = true;
                                                                    return Mono.just(
                                                                            new Row(
                                                                                    item.month(),
                                                                                    "FAILED",
                                                                                    error
                                                                                            .getMessage(),
                                                                                    null));
                                                                });
                                            })
                                    .collectList();
                        });
    }

    static List<Item> ordered(String action, Input input) {
        if (!Set.of("CLOSE", "REOPEN").contains(action)
                || input == null
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 120
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000)
            throw FinanceScope.error("month_closing_invalid", 400);
        var seen = new HashSet<String>();
        for (var item : input.items()) {
            if (item == null
                    || item.month() == null
                    || !item.month().matches("\\d{4}-\\d{2}")
                    || item.version() == null
                    || !seen.add(item.month()))
                throw FinanceScope.error("month_closing_invalid", 400);
            DocumentStore.validateRequest(item.requestId());
        }
        var comparator = Comparator.comparing(Item::month);
        return input.items().stream()
                .sorted("CLOSE".equals(action) ? comparator : comparator.reversed())
                .toList();
    }
}
