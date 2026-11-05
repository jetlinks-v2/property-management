package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.time.LocalDateTime;
import java.util.*;

/** 每批次仍由既有事务原子更新；批量返回逐项结果，不承诺跨批次回滚。 */
@Service
@RequiredArgsConstructor
public class HandoverBatchService {
    private final HandoverRecords records;
    private final HandoverService service;

    public record Item(String id, Long version, String requestId) {}

    public record Input(List<Item> items, String reason) {}

    public record Result(String id, boolean success, String errorCode) {}

    public Mono<List<Result>> preview(String project, String action, Input input) {
        validate(action, input);
        return Flux.fromIterable(input.items())
                .concatMap(
                        item ->
                                service.batchReplay(
                                                project,
                                                item.id(),
                                                action,
                                                new HandoverService.Action(
                                                        item.requestId(),
                                                        item.version(),
                                                        input.reason()))
                                        .map(detail -> new Result(item.id(), true, null))
                                        .switchIfEmpty(
                                                records.detail(project, item.id())
                                                        .flatMap(
                                                                detail ->
                                                                        records.detail(
                                                                                detail.batch(),
                                                                                CrudAssetPermission
                                                                                        .save))
                                                        .flatMap(
                                                                detail ->
                                                                        service.batchCheck(
                                                                                        detail
                                                                                                .batch())
                                                                                .thenReturn(detail))
                                                        .map(
                                                                detail -> {
                                                                    var row = detail.batch();
                                                                    String expected =
                                                                            "UNAPPROVE"
                                                                                            .equals(
                                                                                                    action)
                                                                                    ? "APPROVED"
                                                                                    : "PENDING";
                                                                    boolean valid =
                                                                            Objects.equals(
                                                                                            row
                                                                                                    .getVersion(),
                                                                                            item
                                                                                                    .version())
                                                                                    && expected
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getStatus())
                                                                                    && !Boolean.TRUE
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getMonthClosed());
                                                                    return new Result(
                                                                            item.id(),
                                                                            valid,
                                                                            valid
                                                                                    ? null
                                                                                    : "error.property_finance_handover_state_conflict");
                                                                }))
                                        .onErrorResume(
                                                error ->
                                                        Mono.just(
                                                                new Result(
                                                                        item.id(),
                                                                        false,
                                                                        error.getMessage()))))
                .collectList();
    }

    public Mono<List<Result>> execute(
            String project, String action, Input input, LocalDateTime now) {
        return preview(project, action, input)
                .flatMap(
                        preflight -> {
                            var byId = new HashMap<String, Result>();
                            preflight.forEach(row -> byId.put(row.id(), row));
                            return Flux.fromIterable(input.items())
                                    .concatMap(
                                            item -> {
                                                if (!byId.get(item.id()).success())
                                                    return Mono.just(byId.get(item.id()));
                                                return service.action(
                                                                project,
                                                                item.id(),
                                                                action,
                                                                new HandoverService.Action(
                                                                        item.requestId(),
                                                                        item.version(),
                                                                        input.reason()),
                                                                now)
                                                        .map(
                                                                result ->
                                                                        new Result(
                                                                                item.id(), true,
                                                                                null))
                                                        .onErrorResume(
                                                                error ->
                                                                        Mono.just(
                                                                                new Result(
                                                                                        item.id(),
                                                                                        false,
                                                                                        error
                                                                                                .getMessage())));
                                            })
                                    .collectList();
                        });
    }

    private static void validate(String action, Input input) {
        if (!Set.of("APPROVE", "UNAPPROVE", "REJECT", "CANCEL").contains(action)
                || input == null
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 100
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000)
            throw FinanceScope.error("handover_invalid", 400);
        var ids = new HashSet<String>();
        for (var item : input.items()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.version() == null)
                throw FinanceScope.error("handover_invalid", 400);
            DocumentStore.validateRequest(item.requestId());
        }
    }
}
