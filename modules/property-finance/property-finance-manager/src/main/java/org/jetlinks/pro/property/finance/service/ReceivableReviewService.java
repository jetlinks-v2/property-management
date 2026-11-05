package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 应收按钮审核整批发布，原成功请求可恢复；与工作流无关，也不改变任何资金金额。 */
@Service
@RequiredArgsConstructor
public class ReceivableReviewService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;
    private final ReactiveRepository<ReceivableReviewOperationEntity, String> operations;

    public record Item(String id, Long version) {}

    public record Input(String requestId, String action, List<Item> items, String reason) {}

    /** 只冻结审核结果所需字段，不复制每笔庞大的计费快照到批次结果。 */
    @Data
    public static class Result {
        private String id;
        private String spaceId;
        private Long version;
        private String reviewState;
        private String reviewedOn;
        private String reviewedBy;
        private String reviewedName;
        private String reviewReason;
    }

    @Transactional
    public Mono<List<Result>> submit(String project, Input input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .flatMap(
                                                tenant ->
                                                        Mono.defer(
                                                                () ->
                                                                        authorized(
                                                                                project,
                                                                                tenant,
                                                                                auth,
                                                                                normalize(
                                                                                        input,
                                                                                        date),
                                                                                date))))
                .as(MonoTracer.create("property.finance.receivable.review"));
    }

    private Mono<List<Result>> authorized(
            String project, String tenant, Authentication auth, Input input, LocalDate date) {
        var key =
                DigestUtils.sha256Hex(
                        JSON.toJSONString(
                                List.of(
                                        tenant,
                                        project,
                                        auth.getUser().getId(),
                                        input.requestId())));
        var fingerprint =
                DigestUtils.sha256Hex(
                        JSON.toJSONString(
                                List.of(
                                        input.action(),
                                        input.reason(),
                                        input.items().stream()
                                                .map(item -> List.of(item.id(), item.version()))
                                                .toList())));
        return gate.inOpenPeriod(
                project,
                date,
                replay(project, tenant, key, fingerprint, input),
                () ->
                        locked(project, input)
                                .flatMap(
                                        rows ->
                                                publish(
                                                        project,
                                                        tenant,
                                                        key,
                                                        fingerprint,
                                                        input,
                                                        date,
                                                        auth,
                                                        rows)));
    }

    private Mono<List<ReceivableEntity>> locked(String project, Input input) {
        return Flux.fromIterable(input.items())
                .concatMap(
                        item -> {
                            // QueryParam 会收集查询条件；逐账创建，避免上一账 ID 污染下一账锁查询。
                            var query = new QueryParamEntity();
                            query.setForUpdate(true);
                            return bills.createQuery()
                                    .setParam(query)
                                    .where("id", item.id())
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                                    .flatMap(
                                            row ->
                                                    permission(row.getSpaceId())
                                                            .then(
                                                                    Mono.fromCallable(
                                                                            () -> {
                                                                                if (!Objects.equals(
                                                                                        row
                                                                                                .getVersion(),
                                                                                        item
                                                                                                .version()))
                                                                                    throw error(
                                                                                            "version_conflict",
                                                                                            409);
                                                                                assertAllowed(
                                                                                        row,
                                                                                        input
                                                                                                .action());
                                                                                return row;
                                                                            })));
                        })
                .collectList();
    }

    private Mono<List<Result>> publish(
            String project,
            String tenant,
            String key,
            String fingerprint,
            Input input,
            LocalDate date,
            Authentication auth,
            List<ReceivableEntity> rows) {
        return Flux.fromIterable(rows)
                .concatMap(row -> write(row, input, date, auth))
                .collectList()
                .flatMap(
                        results -> {
                            var operation = new ReceivableReviewOperationEntity();
                            operation.setId(key);
                            InvoiceCompanyService.init(operation, auth);
                            operation.setTenantId(tenant);
                            operation.setProjectId(project);
                            operation.setAction(input.action());
                            operation.setRequestFingerprint(fingerprint);
                            operation.setResultJson(JSON.toJSONString(results));
                            return operations.insert(operation).thenReturn(results);
                        });
    }

    private Mono<Result> write(
            ReceivableEntity row, Input input, LocalDate date, Authentication auth) {
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(row.getProjectId());
        log.setReceivableId(row.getId());
        log.setSpaceId(row.getSpaceId());
        log.setOperation(input.action());
        log.setReason(input.reason());
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(auth.getUser().getUsername());
        log.setCreateTime(System.currentTimeMillis());
        log.setBeforeVersion(row.getVersion());
        log.setBeforeJson(ReceivableService.auditSnapshot(row));
        var beforeVersion = row.getVersion();
        row.setReviewState(
                switch (input.action()) {
                    case "APPROVE" -> "APPROVED";
                    case "REJECT" -> "REJECTED";
                    default -> "PENDING";
                });
        row.setReviewedOn(date.toString());
        row.setReviewedBy(auth.getUser().getId());
        row.setReviewedName(
                auth.getUser().getName() == null
                        ? auth.getUser().getUsername()
                        : auth.getUser().getName());
        row.setReviewReason(input.reason());
        row.setVersion(beforeVersion + 1);
        row.setModifyTime(log.getCreateTime());
        row.setModifierId(auth.getUser().getId());
        row.setModifierName(auth.getUser().getUsername());
        log.setAfterVersion(row.getVersion());
        log.setAfterJson(ReceivableService.auditSnapshot(row));
        var result = new Result();
        org.springframework.beans.BeanUtils.copyProperties(row, result);
        // GenericEntity 的泛型 ID getter 不能由 BeanUtils 稳定映射到 String 属性。
        result.setId(row.getId());
        return InvoiceCompanyService.audit(bills.createUpdate(), auth, beforeVersion)
                .set("reviewState", row.getReviewState())
                .set("reviewedOn", row.getReviewedOn())
                .set("reviewedBy", row.getReviewedBy())
                .set("reviewedName", row.getReviewedName())
                .set("reviewReason", row.getReviewReason())
                .set("modifyTime", row.getModifyTime())
                .where("id", row.getId())
                .and("version", beforeVersion)
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(logs.insert(log))
                .thenReturn(result);
    }

    private Mono<List<Result>> replay(
            String project, String tenant, String key, String fingerprint, Input input) {
        return operations
                .createQuery()
                .where("id", key)
                .and("projectId", project)
                .and("tenantId", tenant)
                .fetchOne()
                .flatMap(
                        operation -> {
                            if (!fingerprint.equals(operation.getRequestFingerprint()))
                                return Mono.error(error("receivable_review_request_conflict", 409));
                            var results = restoredResults(operation, input);
                            return Flux.fromIterable(results)
                                    .concatMap(
                                            result ->
                                                    bills.createQuery()
                                                            .where("id", result.getId())
                                                            .and("projectId", project)
                                                            .fetchOne()
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            error(
                                                                                    "receivable_not_found",
                                                                                    404)))
                                                            .flatMap(
                                                                    row -> {
                                                                        if (!Objects.equals(
                                                                                result.getSpaceId(),
                                                                                row.getSpaceId()))
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "receivable_review_request_conflict",
                                                                                            409));
                                                                        return permission(
                                                                                row.getSpaceId());
                                                                    }))
                                    .then(Mono.just(List.copyOf(results)));
                        });
    }

    /** 成功日志损坏不能被当成空成功，恢复结果必须对应原批次的每笔账及版本。 */
    private List<Result> restoredResults(ReceivableReviewOperationEntity operation, Input input) {
        List<Result> results;
        try {
            results = JSON.parseArray(operation.getResultJson(), Result.class);
        } catch (com.alibaba.fastjson.JSONException invalid) {
            throw error("receivable_review_request_conflict", 409);
        }
        if (results == null
                || results.size() != input.items().size()
                || !Objects.equals(operation.getAction(), input.action()))
            throw error("receivable_review_request_conflict", 409);
        String expected =
                switch (input.action()) {
                    case "APPROVE" -> "APPROVED";
                    case "REJECT" -> "REJECTED";
                    default -> "PENDING";
                };
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            var item = input.items().get(i);
            if (result == null
                    || !Objects.equals(item.id(), result.getId())
                    || !Objects.equals(item.version() + 1, result.getVersion())
                    || !Objects.equals(expected, result.getReviewState())
                    || !Objects.equals(input.reason(), result.getReviewReason())
                    || result.getSpaceId() == null
                    || result.getSpaceId().isBlank())
                throw error("receivable_review_request_conflict", 409);
        }
        return results;
    }

    private Mono<Void> permission(String space) {
        return AssetsHolder.assertPermission(
                AssetType.of("space"), space, CrudAssetPermission.save);
    }

    private void assertAllowed(ReceivableEntity row, String action) {
        if (!org.jetlinks.pro.property.finance.receivable.ReceivablePolicy.reviewAllowed(row)
                .contains(action)) throw error("receivable_review_denied", 409);
    }

    private Input normalize(Input input, LocalDate date) {
        if (input == null
                || date == null
                || input.requestId() == null
                || input.requestId().isBlank()
                || input.requestId().length() > 64
                || input.action() == null
                || !Set.of("APPROVE", "REJECT", "UNAPPROVE").contains(input.action())
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 100
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("receivable_review_invalid", 400);
        var ids = new HashSet<String>();
        for (var item : input.items())
            if (item == null
                    || item.id() == null
                    || item.id().isBlank()
                    || item.id().length() > 64
                    || !ids.add(item.id())
                    || item.version() == null
                    || item.version() < 0) throw error("receivable_review_invalid", 400);
        return new Input(
                input.requestId(),
                input.action(),
                input.items().stream().sorted(Comparator.comparing(Item::id)).toList(),
                input.reason().trim());
    }
}
