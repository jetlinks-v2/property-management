package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 交账、来源绑定、原存入保护和审计整批同事务；任何拒绝都不能留下部分成功。 */
@Service
@RequiredArgsConstructor
public class HandoverService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HandoverSources sources;
    private final HandoverRecords records;
    private final ReactiveRepository<HandoverEntity, String> batches;
    private final ReactiveRepository<HandoverLineEntity, String> lines;
    private final ReactiveRepository<HandoverSourceEntity, String> bindings;
    private final ReactiveRepository<HandoverOperationEntity, String> operations;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentLineEntity, String> documentLines;
    private final ReactiveRepository<FinancialPeriodControlEntity, String> periodControls;

    public record Attachment(String fileId, String name, Long size) {}

    public record Input(
            List<HandoverSources.Item> items,
            String closingDate,
            String remark,
            String attachmentFileId,
            String attachmentName,
            List<Attachment> attachments) {
        public Input(
                List<HandoverSources.Item> items,
                String closingDate,
                String remark,
                String attachmentFileId,
                String attachmentName) {
            this(items, closingDate, remark, attachmentFileId, attachmentName, List.of());
        }
    }

    public record Preview(
            String token,
            List<HandoverLineEntity> lines,
            String amount,
            String cashAmount,
            int sourceCount) {}

    public record Submission(String requestId, String token, Input input) {}

    public record Action(String requestId, Long version, String reason) {}

    public Mono<Preview> preview(String project, Input input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    var i = normalize(input, now);
                                    return sources.load(
                                                    project,
                                                    i.items(),
                                                    false,
                                                    CrudAssetPermission.save)
                                            .map(rows -> preview(project, i, rows));
                                }));
    }

    @Transactional
    public Mono<HandoverRecords.Detail> confirm(
            String project, Submission request, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (request == null
                                                    || request.token() == null
                                                    || !request.token().matches("[a-f0-9]{64}"))
                                                throw error("handover_invalid", 400);
                                            DocumentStore.validateRequest(request.requestId());
                                            var input = normalize(request.input(), now);
                                            var fingerprint =
                                                    PaymentRules.digest(
                                                            JSON.toJSONString(
                                                                    List.of(
                                                                            request.token(),
                                                                            inputKey(input))));
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant -> {
                                                                var id =
                                                                        DocumentStore.requestKey(
                                                                                tenant,
                                                                                project,
                                                                                auth,
                                                                                "HANDOVER",
                                                                                "CREATE",
                                                                                request
                                                                                        .requestId());
                                                                return periodGate.inOpenPeriod(
                                                                        project,
                                                                        LocalDate.parse(
                                                                                input
                                                                                        .closingDate()),
                                                                        replay(
                                                                                project,
                                                                                id,
                                                                                tenant,
                                                                                fingerprint),
                                                                        () ->
                                                                                sources.load(
                                                                                                project,
                                                                                                input
                                                                                                        .items(),
                                                                                                true,
                                                                                                CrudAssetPermission
                                                                                                        .save)
                                                                                        .flatMap(
                                                                                                rows ->
                                                                                                        replay(
                                                                                                                        project,
                                                                                                                        id,
                                                                                                                        tenant,
                                                                                                                        fingerprint)
                                                                                                                .switchIfEmpty(
                                                                                                                        Mono
                                                                                                                                .defer(
                                                                                                                                        () -> {
                                                                                                                                            var
                                                                                                                                                    quote =
                                                                                                                                                            preview(
                                                                                                                                                                    project,
                                                                                                                                                                    input,
                                                                                                                                                                    rows);
                                                                                                                                            if (!request.token()
                                                                                                                                                    .equals(
                                                                                                                                                            quote
                                                                                                                                                                    .token()))
                                                                                                                                                return Mono
                                                                                                                                                        .error(
                                                                                                                                                                error(
                                                                                                                                                                        "handover_stale",
                                                                                                                                                                        409));
                                                                                                                                            return claim(
                                                                                                                                                            project,
                                                                                                                                                            tenant,
                                                                                                                                                            id,
                                                                                                                                                            rows,
                                                                                                                                                            auth)
                                                                                                                                                    .then(
                                                                                                                                                            write(
                                                                                                                                                                    project,
                                                                                                                                                                    tenant,
                                                                                                                                                                    id,
                                                                                                                                                                    fingerprint,
                                                                                                                                                                    input,
                                                                                                                                                                    quote,
                                                                                                                                                                    now,
                                                                                                                                                                    auth));
                                                                                                                                        }))));
                                                            });
                                        }))
                .onErrorMap(HandoverService::duplicate)
                .as(MonoTracer.create("property.finance.handover.confirm"));
    }

    private Mono<HandoverRecords.Detail> replay(
            String project, String id, String tenant, String fingerprint) {
        return batches.createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne()
                .flatMap(
                        row -> {
                            if (!fingerprint.equals(row.getRequestFingerprint()))
                                return Mono.error(error("handover_request_conflict", 409));
                            return records.detail(row, CrudAssetPermission.save);
                        });
    }

    private Mono<Void> claim(
            String project,
            String tenant,
            String id,
            List<HandoverSources.Source> rows,
            Authentication auth) {
        return Flux.fromIterable(rows)
                .concatMap(
                        s -> {
                            var key = bindingKey(tenant, project, s.type(), s.id());
                            return bindings.findById(key)
                                    .flatMap(
                                            existing -> {
                                                if (existing.getBatchId() != null)
                                                    return Mono.<Boolean>error(
                                                            error("handover_claimed", 409));
                                                return audit(
                                                                bindings.createUpdate(),
                                                                auth,
                                                                existing.getVersion())
                                                        .set("tenantId", existing.getTenantId())
                                                        .set("batchId", id)
                                                        .where("id", key)
                                                        .and("version", existing.getVersion())
                                                        .isNull("batchId")
                                                        .execute()
                                                        .flatMap(SubjectService::changed)
                                                        .thenReturn(true);
                                            })
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () -> {
                                                        var b = new HandoverSourceEntity();
                                                        init(b, auth);
                                                        b.setId(key);
                                                        b.setTenantId(tenant);
                                                        b.setProjectId(project);
                                                        b.setSourceType(s.type());
                                                        b.setSourceId(s.id());
                                                        b.setBatchId(id);
                                                        return bindings.insert(b).thenReturn(true);
                                                    }))
                                    .then(sources.markCredit(project, s, true, auth));
                        })
                .then();
    }

    private Mono<HandoverRecords.Detail> write(
            String project,
            String tenant,
            String id,
            String fingerprint,
            Input input,
            Preview quote,
            LocalDateTime now,
            Authentication auth) {
        var row = new HandoverEntity();
        init(row, auth);
        row.setId(id);
        row.setTenantId(tenant);
        row.setProjectId(project);
        row.setRequestFingerprint(fingerprint);
        row.setStatus("PENDING");
        row.setHandoverNo(
                "JZ-"
                        + input.closingDate().replace("-", "")
                        + "-"
                        + id.substring(0, 12).toUpperCase(java.util.Locale.ROOT));
        row.setAttachmentsJson(
                JSON.toJSONString(
                        input.attachments().stream()
                                .map(
                                        file ->
                                                Map.of(
                                                        "fileId",
                                                        file.fileId(),
                                                        "name",
                                                        file.name(),
                                                        "size",
                                                        file.size()))
                                .toList()));
        row.setClosingDate(input.closingDate());
        row.setSubmittedAt(now.toString());
        row.setSubmittedBy(displayName(auth));
        row.setSourceCount(quote.sourceCount());
        row.setAmount(new BigDecimal(quote.amount()));
        row.setCashAmount(new BigDecimal(quote.cashAmount()));
        row.setRemark(input.remark());
        row.setAttachmentFileId(input.attachmentFileId());
        row.setAttachmentName(input.attachmentName());
        return batches.insert(row)
                .thenMany(
                        Flux.range(0, quote.lines().size())
                                .concatMap(
                                        index -> {
                                            var l = quote.lines().get(index);
                                            init(l, auth);
                                            l.setId(PaymentRules.digest(id + ":" + index));
                                            l.setProjectId(project);
                                            l.setBatchId(id);
                                            return lines.insert(l);
                                        }))
                .then(log(id, row, "CREATE", fingerprint, null, input.remark(), auth))
                .then(records.detail(row, CrudAssetPermission.save));
    }

    /** 预检只读现有边界，不初始化或锁期间；写入仍由既有事务门闩重验。 */
    Mono<Void> batchCheck(HandoverEntity row) {
        return TenantHolder.currentId()
                .defaultIfEmpty("")
                .flatMap(
                        tenant ->
                                periodControls
                                        .createQuery()
                                        .where("projectId", row.getProjectId())
                                        .and("tenantId", tenant)
                                        .and("deleted", false)
                                        .fetchOne()
                                        .flatMap(
                                                control ->
                                                        periodGate.assertOpen(
                                                                control,
                                                                LocalDate.parse(
                                                                        row.getClosingDate()))));
    }

    /** 批量重试先恢复原操作身份，旧版本不会被误判为新的审核动作。 */
    Mono<HandoverRecords.Detail> batchReplay(
            String project, String id, String action, Action input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .flatMap(
                                                tenant -> {
                                                    var fingerprint =
                                                            PaymentRules.digest(
                                                                    JSON.toJSONString(
                                                                            List.of(
                                                                                    id,
                                                                                    action,
                                                                                    input.version(),
                                                                                    input
                                                                                            .reason())));
                                                    var key =
                                                            DocumentStore.requestKey(
                                                                    tenant,
                                                                    project,
                                                                    auth,
                                                                    "HANDOVER",
                                                                    action,
                                                                    input.requestId());
                                                    return replayAction(
                                                            project, id, key, tenant, fingerprint);
                                                }));
    }

    @Transactional
    public Mono<HandoverRecords.Detail> action(
            String project, String id, String action, Action input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (input == null
                                                    || input.version() == null
                                                    || input.version() < 0
                                                    || input.reason() == null
                                                    || input.reason().isBlank()
                                                    || input.reason().length() > 1000
                                                    || !Set.of(
                                                                    "APPROVE",
                                                                    "REJECT",
                                                                    "UNAPPROVE",
                                                                    "CANCEL")
                                                            .contains(action))
                                                throw error("handover_invalid", 400);
                                            DocumentStore.validateRequest(input.requestId());
                                            var fingerprint =
                                                    PaymentRules.digest(
                                                            JSON.toJSONString(
                                                                    List.of(
                                                                            id,
                                                                            action,
                                                                            input.version(),
                                                                            input.reason())));
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant -> {
                                                                var key =
                                                                        DocumentStore.requestKey(
                                                                                tenant,
                                                                                project,
                                                                                auth,
                                                                                "HANDOVER",
                                                                                action,
                                                                                input.requestId());
                                                                return periodGate.inOpenPeriod(
                                                                        project,
                                                                        now.toLocalDate(),
                                                                        replayAction(
                                                                                project,
                                                                                id,
                                                                                key,
                                                                                tenant,
                                                                                fingerprint),
                                                                        () ->
                                                                                records.require(
                                                                                                project,
                                                                                                id,
                                                                                                tenant,
                                                                                                false)
                                                                                        .flatMap(
                                                                                                row ->
                                                                                                        records
                                                                                                                .detail(
                                                                                                                        row,
                                                                                                                        CrudAssetPermission
                                                                                                                                .save))
                                                                                        .flatMap(
                                                                                                d -> {
                                                                                                    var
                                                                                                            items =
                                                                                                                    d
                                                                                                                            .lines()
                                                                                                                            .stream()
                                                                                                                            .map(
                                                                                                                                    l ->
                                                                                                                                            new HandoverSources
                                                                                                                                                    .Item(
                                                                                                                                                    l
                                                                                                                                                            .getSourceType(),
                                                                                                                                                    l
                                                                                                                                                            .getSourceId(),
                                                                                                                                                    0L))
                                                                                                                            .distinct()
                                                                                                                            .sorted(
                                                                                                                                    Comparator
                                                                                                                                            .comparing(
                                                                                                                                                    HandoverSources
                                                                                                                                                                    .Item
                                                                                                                                                            ::type)
                                                                                                                                            .thenComparing(
                                                                                                                                                    HandoverSources
                                                                                                                                                                    .Item
                                                                                                                                                            ::id))
                                                                                                                            .toList();
                                                                                                    // 与创建相同的来源锁序；来源锁之后再锁批次，禁止取消/再交账出现反向锁序。
                                                                                                    return sources.load(
                                                                                                                    project,
                                                                                                                    items,
                                                                                                                    true,
                                                                                                                    CrudAssetPermission
                                                                                                                            .save)
                                                                                                            .flatMap(
                                                                                                                    rows ->
                                                                                                                            records.require(
                                                                                                                                            project,
                                                                                                                                            id,
                                                                                                                                            tenant,
                                                                                                                                            true)
                                                                                                                                    .flatMap(
                                                                                                                                            locked ->
                                                                                                                                                    replayAction(
                                                                                                                                                                    project,
                                                                                                                                                                    id,
                                                                                                                                                                    key,
                                                                                                                                                                    tenant,
                                                                                                                                                                    fingerprint)
                                                                                                                                                            .switchIfEmpty(
                                                                                                                                                                    Mono
                                                                                                                                                                            .defer(
                                                                                                                                                                                    () ->
                                                                                                                                                                                            transition(
                                                                                                                                                                                                    locked,
                                                                                                                                                                                                    rows,
                                                                                                                                                                                                    key,
                                                                                                                                                                                                    action,
                                                                                                                                                                                                    input,
                                                                                                                                                                                                    fingerprint,
                                                                                                                                                                                                    now,
                                                                                                                                                                                                    auth)))));
                                                                                                }));
                                                            });
                                        }))
                .onErrorMap(HandoverService::duplicate)
                .as(MonoTracer.create("property.finance.handover.transition"));
    }

    private Mono<HandoverRecords.Detail> transition(
            HandoverEntity row,
            List<HandoverSources.Source> sourceRows,
            String key,
            String action,
            Action input,
            String fingerprint,
            LocalDateTime now,
            Authentication auth) {
        var before = row.getStatus();
        var after =
                switch (action) {
                    case "APPROVE" -> "APPROVED";
                    case "UNAPPROVE" -> "PENDING";
                    case "REJECT" -> "REJECTED";
                    default -> "CANCELLED";
                };
        if (!Objects.equals(input.version(), row.getVersion())
                || Boolean.TRUE.equals(row.getMonthClosed())
                || !("UNAPPROVE".equals(action)
                        ? "APPROVED".equals(before)
                        : "PENDING".equals(before)))
            return Mono.error(error("handover_state_conflict", 409));
        var release = Set.of("REJECT", "CANCEL").contains(action);
        // 批次所属日期已结后，即使操作日进入下一账期，也不能改动原交账审核事实。
        Mono<Void> guard =
                periodGate
                        .assertOpen(row.getProjectId(), LocalDate.parse(row.getClosingDate()))
                        .then(release ? checkReceipts(row, sourceRows) : Mono.empty());
        return guard.then(release ? release(row, sourceRows, auth) : Mono.empty())
                .then(
                        Mono.defer(
                                () -> {
                                    // 审核只改变交账状态，显式保留原归属，防止身份默认租户自动补写更新列。
                                    var update =
                                            audit(batches.createUpdate(), auth, row.getVersion())
                                                    .set("tenantId", row.getTenantId())
                                                    .set("status", after)
                                                    .set("reviewedAt", now.toString())
                                                    .set("reviewedBy", displayName(auth))
                                                    .set("reviewRemark", input.reason().trim());
                                    return update.where("id", row.getId())
                                            .and("version", row.getVersion())
                                            .and("deleted", false)
                                            .execute()
                                            .flatMap(SubjectService::changed)
                                            .then(
                                                    Mono.defer(
                                                            () -> {
                                                                row.setVersion(
                                                                        row.getVersion() + 1);
                                                                row.setStatus(after);
                                                                return log(
                                                                        key,
                                                                        row,
                                                                        action,
                                                                        fingerprint,
                                                                        before,
                                                                        input.reason().trim(),
                                                                        auth);
                                                            }))
                                            .then(
                                                    records.require(
                                                            row.getProjectId(),
                                                            row.getId(),
                                                            row.getTenantId(),
                                                            false))
                                            .flatMap(
                                                    saved ->
                                                            records.detail(
                                                                    saved,
                                                                    CrudAssetPermission.save));
                                }));
    }

    private Mono<Void> release(
            HandoverEntity row, List<HandoverSources.Source> sourceRows, Authentication auth) {
        return Flux.fromIterable(sourceRows)
                .concatMap(
                        s ->
                                bindings.findById(
                                                bindingKey(
                                                        row.getTenantId(),
                                                        row.getProjectId(),
                                                        s.type(),
                                                        s.id()))
                                        .switchIfEmpty(
                                                Mono.error(error("handover_state_conflict", 409)))
                                        .flatMap(
                                                b -> {
                                                    if (!row.getId().equals(b.getBatchId()))
                                                        return Mono.error(
                                                                error(
                                                                        "handover_state_conflict",
                                                                        409));
                                                    return audit(
                                                                    bindings.createUpdate(),
                                                                    auth,
                                                                    b.getVersion())
                                                            .set("tenantId", b.getTenantId())
                                                            .setNull("batchId")
                                                            .where("id", b.getId())
                                                            .and("version", b.getVersion())
                                                            .and("batchId", row.getId())
                                                            .execute()
                                                            .flatMap(SubjectService::changed)
                                                            .then(
                                                                    sources.markCredit(
                                                                            row.getProjectId(),
                                                                            s,
                                                                            false,
                                                                            auth));
                                                }))
                .then();
    }

    private Mono<Void> checkReceipts(HandoverEntity row, List<HandoverSources.Source> sourceRows) {
        var related = new TreeSet<String>();
        for (var s : sourceRows)
            for (var l : s.lines()) {
                if ("PAYMENT".equals(s.type()))
                    related.add(
                            l.getReceivableId() == null
                                    ? "TEMP_PAYMENT:" + s.id()
                                    : "RECEIVABLE:" + l.getReceivableId());
                if ("FUND".equals(s.type())) related.add("FUND_TOPUP:" + s.id());
            }
        return Flux.fromIterable(related)
                .concatMap(
                        key -> {
                            var split = key.indexOf(':');
                            return documentLines
                                    .createQuery()
                                    .setParam(HandoverSources.param(false))
                                    .where("projectId", row.getProjectId())
                                    .and("deleted", false)
                                    .and("sourceType", key.substring(0, split))
                                    .and("sourceId", key.substring(split + 1))
                                    .fetch();
                        })
                .map(DocumentLineEntity::getDocumentId)
                .distinct()
                .sort()
                .concatMap(
                        id ->
                                documents
                                        .createQuery()
                                        .setParam(HandoverSources.param(true))
                                        .where("id", id)
                                        .and("projectId", row.getProjectId())
                                        .and("tenantId", row.getTenantId())
                                        .and("documentType", "RECEIPT")
                                        .and("deleted", false)
                                        .fetchOne()
                                        .doOnNext(
                                                d -> {
                                                    if (Boolean.TRUE.equals(d.getVerified()))
                                                        throw error(
                                                                "handover_receipt_verified", 409);
                                                }))
                .then();
    }

    private Mono<HandoverRecords.Detail> replayAction(
            String project, String batch, String key, String tenant, String fingerprint) {
        return operations
                .findById(key)
                .flatMap(
                        log -> {
                            if (!project.equals(log.getProjectId())
                                    || !batch.equals(log.getBatchId())
                                    || !fingerprint.equals(log.getRequestFingerprint()))
                                return Mono.error(error("handover_request_conflict", 409));
                            return records.require(project, batch, tenant, false)
                                    .flatMap(row -> records.detail(row, CrudAssetPermission.save));
                        });
    }

    private Mono<Void> log(
            String key,
            HandoverEntity row,
            String action,
            String fingerprint,
            String before,
            String reason,
            Authentication auth) {
        var log = new HandoverOperationEntity();
        init(log, auth);
        log.setId(key);
        log.setProjectId(row.getProjectId());
        log.setBatchId(row.getId());
        log.setAction(action);
        log.setRequestFingerprint(fingerprint);
        log.setBeforeStatus(before);
        log.setAfterStatus(row.getStatus());
        log.setReason(reason);
        return operations.insert(log).then();
    }

    private static Preview preview(String project, Input input, List<HandoverSources.Source> rows) {
        var all = rows.stream().flatMap(s -> s.lines().stream()).toList();
        if (all.size() > 10000) throw error("handover_limit", 400);
        for (int n = 0; n < rows.size(); n++) {
            var s = rows.get(n);
            if (!Objects.equals(s.version(), input.items().get(n).version()))
                throw error("handover_stale", 409);
            for (var line : s.lines())
                if (LocalDateTime.parse(line.getOccurredAt())
                        .toLocalDate()
                        .isAfter(LocalDate.parse(input.closingDate())))
                    throw error("handover_date_invalid", 400);
        }
        var amount = HandoverSources.sum(all, false);
        var cash = HandoverSources.sum(all, true);
        if (amount.precision() > 20 || cash.precision() > 20) throw error("handover_limit", 400);
        var facts =
                rows.stream().map(s -> List.of(s.type(), s.id(), s.version(), s.lines())).toList();
        return new Preview(
                PaymentRules.digest(JSON.toJSONString(List.of(project, inputKey(input), facts))),
                all,
                amount.toPlainString(),
                cash.toPlainString(),
                rows.size());
    }

    private static Input normalize(Input input, LocalDateTime now) {
        if (input == null
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 100
                || tooLong(input.remark(), 1000)
                || tooLong(input.attachmentFileId(), 64)
                || tooLong(input.attachmentName(), 255)
                || blank(input.attachmentFileId()) != blank(input.attachmentName()))
            throw error("handover_invalid", 400);
        try {
            if (LocalDate.parse(input.closingDate()).isAfter(now.toLocalDate()))
                throw error("handover_date_invalid", 400);
        } catch (DateTimeException | NullPointerException e) {
            throw error("handover_date_invalid", 400);
        }
        var attachments = input.attachments() == null ? List.<Attachment>of() : input.attachments();
        if (attachments.size() > 5) throw error("handover_invalid", 400);
        var fileIds = new HashSet<String>();
        for (var file : attachments)
            if (file == null
                    || blank(file.fileId())
                    || tooLong(file.fileId(), 64)
                    || blank(file.name())
                    || tooLong(file.name(), 255)
                    || file.size() == null
                    || file.size() < 0
                    || file.size() > 20L * 1024 * 1024
                    || !fileIds.add(file.fileId())) throw error("handover_invalid", 400);
        var seen = new HashSet<String>();
        for (var i : input.items())
            if (i == null
                    || !Set.of("PAYMENT", "REFUND", "FUND").contains(Objects.toString(i.type(), ""))
                    || blank(i.id())
                    || i.id().length() > 64
                    || i.version() == null
                    || i.version() < 0
                    || !seen.add(i.type() + ":" + i.id())) throw error("handover_invalid", 400);
        var sorted =
                input.items().stream()
                        .sorted(
                                Comparator.comparing(HandoverSources.Item::type)
                                        .thenComparing(HandoverSources.Item::id))
                        .toList();
        return new Input(
                sorted,
                input.closingDate(),
                trim(input.remark()),
                trim(input.attachmentFileId()),
                trim(input.attachmentName()),
                List.copyOf(attachments));
    }

    private static String bindingKey(String tenant, String project, String type, String id) {
        return PaymentRules.digest(JSON.toJSONString(List.of(tenant, project, type, id)));
    }

    // 指纹显式列出契约字段，避免序列化器对Java record的属性发现差异遗漏载荷。
    private static Object inputKey(Input i) {
        return Arrays.asList(
                i.closingDate(),
                i.remark(),
                i.attachmentFileId(),
                i.attachmentName(),
                i.attachments().stream()
                        .map(a -> Arrays.asList(a.fileId(), a.name(), a.size()))
                        .toList(),
                i.items().stream().map(v -> List.of(v.type(), v.id(), v.version())).toList());
    }

    private static String displayName(Authentication auth) {
        var u = auth.getUser();
        return blank(u.getName()) ? u.getUsername() : u.getName();
    }

    private static boolean tooLong(String s, int n) {
        return s != null && s.length() > n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    private static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("handover_claimed", 409);
        return failure;
    }
}
