package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 本地按钮审批：申请快照与正式账隔离；项目期间锁串行化申请、收款、任务及批准。 */
@Service
@RequiredArgsConstructor
public class ReceivableChangeAuditService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final ReactiveRepository<ReceivableChangeEntity, String> changes;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;
    private final ReceivableAdjustmentService adjustments;
    private final ReceivableSplitService splits;

    @Transactional
    public Mono<ReceivableChangeEntity> requestAdjustment(
            String project,
            String id,
            ReceivableAdjustmentService.SubmitInput input,
            LocalDate today) {
        if (input == null || input.input() == null) return Mono.error(error("change_invalid", 400));
        return authorizeWrite(project, today)
                .then(bill(project, id))
                .flatMap(
                        source ->
                                adjustments
                                        .preview(project, id, input.input(), today)
                                        .flatMap(
                                                preview -> {
                                                    if (!Objects.equals(
                                                            preview.token(), input.token()))
                                                        return Mono.error(
                                                                error(
                                                                        "adjustment_preview_expired",
                                                                        409));
                                                    return save(
                                                            source,
                                                            "ADJUST",
                                                            input.input().getReason(),
                                                            JSON.toJSONString(input.input()),
                                                            adjustmentSnapshot(preview));
                                                }))
                .as(MonoTracer.create("property.finance.receivable.change.request-adjust"));
    }

    @Transactional
    public Mono<ReceivableChangeEntity> requestSplit(
            String project, String id, ReceivableSplitService.Submit input, LocalDate today) {
        if (input == null || input.input() == null) return Mono.error(error("change_invalid", 400));
        return authorizeWrite(project, today)
                .then(bill(project, id))
                .flatMap(
                        source ->
                                splits.preview(project, id, input.input(), today)
                                        .flatMap(
                                                preview -> {
                                                    if (!Objects.equals(
                                                            preview.token(), input.token()))
                                                        return Mono.error(
                                                                error(
                                                                        "split_preview_expired",
                                                                        409));
                                                    var values = input.input();
                                                    var payload =
                                                            Map.of(
                                                                    "version",
                                                                    values.version(),
                                                                    "splitDate",
                                                                    values.splitDate().toString(),
                                                                    "scale",
                                                                    values.scale(),
                                                                    "firstRelationshipId",
                                                                    values.firstRelationshipId(),
                                                                    "secondRelationshipId",
                                                                    values.secondRelationshipId(),
                                                                    "reason",
                                                                    values.reason());
                                                    return save(
                                                            source,
                                                            "SPLIT",
                                                            values.reason(),
                                                            JSON.toJSONString(payload),
                                                            splitSnapshot(preview));
                                                }))
                .as(MonoTracer.create("property.finance.receivable.change.request-split"));
    }

    private Mono<Void> authorizeWrite(String project, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, today));
    }

    private Mono<ReceivableChangeEntity> save(
            ReceivableEntity source,
            String operation,
            String reason,
            String payload,
            JSONObject preview) {
        // 期间锁下检查同一账单只允许一份待审批申请；历史批准/驳回不阻止再次申请。
        return changes.createQuery()
                .where("projectId", source.getProjectId())
                .and("receivableId", source.getId())
                .and("status", "PENDING")
                .and("deleted", false)
                .count()
                .flatMap(
                        count -> {
                            if (count > 0) return Mono.error(error("change_pending", 409));
                            return scope.authenticated()
                                    .flatMap(
                                            auth -> {
                                                var row = new ReceivableChangeEntity();
                                                row.setId(UUID.randomUUID().toString());
                                                init(row, auth);
                                                row.setProjectId(source.getProjectId());
                                                row.setSpaceId(source.getSpaceId());
                                                row.setReceivableId(source.getId());
                                                row.setSourceVersion(source.getVersion());
                                                row.setFullName(source.getFullName());
                                                row.setCustomerName(source.getCustomerName());
                                                row.setSubjectName(source.getSubjectName());
                                                row.setOperation(operation);
                                                row.setStatus("PENDING");
                                                row.setReason(reason.trim());
                                                row.setApplicantId(auth.getUser().getId());
                                                row.setApplicantName(auth.getUser().getUsername());
                                                row.setInputJson(payload);
                                                row.setPreviewJson(preview.toJSONString());
                                                row.setBeforeJson(JSON.toJSONString(source));
                                                return changes.insert(row)
                                                        .then(
                                                                log(
                                                                        row,
                                                                        auth,
                                                                        "CHANGE_REQUEST",
                                                                        row.getReason()))
                                                        .thenReturn(row);
                                            });
                        });
    }

    public Mono<PagerResult<ReceivableChangeEntity>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.setForUpdate(false);
                                    query.toNestQuery(
                                            n -> n.and("projectId", project).and("deleted", false));
                                    query.toQuery().orderByDesc("createTime");
                                    return FinanceSpaceQuery.inject(
                                                    query, changes, CrudAssetPermission.read)
                                            .flatMap(
                                                    q ->
                                                            QueryHelper.queryPager(
                                                                    q, changes::createQuery));
                                }));
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, CrudAssetPermission.read))
                .map(
                        row ->
                                new Detail(
                                        row,
                                        JSON.parseObject(row.getInputJson()),
                                        JSON.parseObject(row.getPreviewJson()),
                                        ReceivableView.of(
                                                JSON.parseObject(
                                                        row.getBeforeJson(),
                                                        ReceivableEntity.class),
                                                true)));
    }

    @Transactional
    public Mono<ReceivableChangeEntity> decide(
            String project, String id, Decision input, boolean approve, LocalDate today) {
        if (input == null
                || input.version() == null
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) return Mono.error(error("change_invalid", 400));
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.lock(project))
                .then(row(project, id, true, CrudAssetPermission.save))
                .flatMap(
                        row -> {
                            if (!"PENDING".equals(row.getStatus())
                                    || !Objects.equals(row.getVersion(), input.version()))
                                return Mono.error(error("version_conflict", 409));
                            // 驳回不受原账后来收款或结账阻碍；批准必须重新确认当前开放期间和源版本。
                            Mono<Void> apply =
                                    approve
                                            ? periodGate
                                                    .assertOpen(project, today)
                                                    .then(apply(row, today))
                                            : Mono.empty();
                            return apply.then(scope.authenticated())
                                    .flatMap(auth -> finish(row, input, approve, auth));
                        })
                .as(MonoTracer.create("property.finance.receivable.change.decide"));
    }

    private Mono<Void> apply(ReceivableChangeEntity row, LocalDate today) {
        return bill(row.getProjectId(), row.getReceivableId())
                .flatMap(
                        source -> {
                            if (!Objects.equals(source.getVersion(), row.getSourceVersion()))
                                return Mono.error(error("change_source_changed", 409));
                            if ("ADJUST".equals(row.getOperation())) {
                                var input =
                                        JSON.parseObject(row.getInputJson(), AdjustmentInput.class);
                                return adjustments
                                        .preview(
                                                row.getProjectId(),
                                                row.getReceivableId(),
                                                input,
                                                today)
                                        .flatMap(
                                                preview -> {
                                                    assertSamePreview(
                                                            row, adjustmentSnapshot(preview));
                                                    return adjustments.submit(
                                                            row.getProjectId(),
                                                            row.getReceivableId(),
                                                            new ReceivableAdjustmentService
                                                                    .SubmitInput(
                                                                    input, preview.token()),
                                                            today);
                                                })
                                        .then();
                            }
                            if (!"SPLIT".equals(row.getOperation()))
                                return Mono.error(error("change_invalid", 400));
                            var payload = JSON.parseObject(row.getInputJson());
                            var input =
                                    new SplitInput(
                                            payload.getLong("version"),
                                            LocalDate.parse(payload.getString("splitDate")),
                                            payload.getInteger("scale"),
                                            payload.getString("firstRelationshipId"),
                                            payload.getString("secondRelationshipId"),
                                            payload.getString("reason"));
                            return splits.preview(
                                            row.getProjectId(), row.getReceivableId(), input, today)
                                    .flatMap(
                                            preview -> {
                                                assertSamePreview(row, splitSnapshot(preview));
                                                return splits.submit(
                                                        row.getProjectId(),
                                                        row.getReceivableId(),
                                                        new ReceivableSplitService.Submit(
                                                                input, preview.token()),
                                                        today);
                                            })
                                    .then();
                        });
    }

    private void assertSamePreview(ReceivableChangeEntity row, JSONObject current) {
        if (!JSON.parseObject(row.getPreviewJson())
                .equals(JSON.parseObject(current.toJSONString())))
            throw error("change_preview_changed", 409);
    }

    private Mono<ReceivableChangeEntity> finish(
            ReceivableChangeEntity row, Decision input, boolean approve, Authentication auth) {
        var status = approve ? "APPROVED" : "REJECTED";
        long now = System.currentTimeMillis();
        return audit(changes.createUpdate(), auth, row.getVersion())
                .set("status", status)
                .set("reviewReason", input.reason().trim())
                .set("reviewedBy", auth.getUser().getId())
                .set("reviewedName", auth.getUser().getUsername())
                .set("reviewedAt", now)
                .where("id", row.getId())
                .and("projectId", row.getProjectId())
                .and("version", row.getVersion())
                .and("status", "PENDING")
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(
                        log(
                                row,
                                auth,
                                approve ? "CHANGE_APPROVE" : "CHANGE_REJECT",
                                input.reason().trim()))
                .then(row(row.getProjectId(), row.getId(), false, CrudAssetPermission.read));
    }

    private Mono<Void> log(
            ReceivableChangeEntity row, Authentication auth, String action, String reason) {
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(row.getProjectId());
        log.setSpaceId(row.getSpaceId());
        log.setReceivableId(row.getReceivableId());
        log.setOperation(action);
        log.setReason(reason);
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(auth.getUser().getUsername());
        log.setCreateTime(System.currentTimeMillis());
        log.setDetailJson(
                JSON.toJSONString(
                        Map.of("changeId", row.getId(), "operation", row.getOperation())));
        return bills.findById(row.getReceivableId())
                .flatMap(
                        current -> {
                            var before =
                                    "CHANGE_APPROVE".equals(action)
                                            ? JSON.parseObject(
                                                    row.getBeforeJson(), ReceivableEntity.class)
                                            : current;
                            log.setBeforeVersion(before.getVersion());
                            log.setAfterVersion(current.getVersion());
                            log.setBeforeJson(ReceivableService.auditSnapshot(before));
                            log.setAfterJson(ReceivableService.auditSnapshot(current));
                            return logs.insert(log);
                        })
                .then();
    }

    private Mono<ReceivableEntity> bill(String project, String id) {
        return bills.createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                SPACE, row.getSpaceId(), CrudAssetPermission.save)
                                        .thenReturn(row));
    }

    private Mono<ReceivableChangeEntity> row(
            String project, String id, boolean lock, CrudAssetPermission permission) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return changes.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("change_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(SPACE, row.getSpaceId(), permission)
                                        .thenReturn(row));
    }

    private static JSONObject adjustmentSnapshot(AdjustmentRules.Result result) {
        var value = new JSONObject();
        value.put("delta", result.delta().toPlainString());
        value.put("before", amounts(result.effectiveBefore()));
        value.put("after", amounts(result.effectiveAfter()));
        // 截至日可跨天，但审批看到的金额及计费日期不能被静默替换；变化时要求重新申请。
        return value;
    }

    private static JSONObject amounts(ReceivableEntity row) {
        var value = new JSONObject();
        value.put("contractReceivableAmount", row.getContractReceivableAmount().toPlainString());
        value.put("accruedReceivableAmount", row.getAccruedReceivableAmount().toPlainString());
        value.put("adjustmentAmount", row.getAdjustmentAmount().toPlainString());
        value.put("assessmentAmount", row.getAssessmentAmount().toPlainString());
        value.put("temporaryDispatchAmount", row.getTemporaryDispatchAmount().toPlainString());
        value.put("feeReductionAmount", row.getFeeReductionAmount().toPlainString());
        value.put("accruedLateFeeAmount", row.getAccruedLateFeeAmount().toPlainString());
        value.put("lateFeeReductionAmount", row.getLateFeeReductionAmount().toPlainString());
        value.put("finalLateFeeAmount", row.getFinalLateFeeAmount().toPlainString());
        value.put("actualReceivableAmount", row.getActualReceivableAmount().toPlainString());
        value.put("arrears", row.getArrears().toPlainString());
        value.put("calcStartDate", row.getCalcStartDate());
        value.put("calcEndDate", row.getCalcEndDate());
        return value;
    }

    private static JSONObject splitSnapshot(ReceivableSplitService.Preview result) {
        var value = new JSONObject();
        value.put("total", result.total());
        value.put(
                "parts",
                result.parts().stream()
                        .map(
                                part ->
                                        Map.of(
                                                "start",
                                                part.start(),
                                                "end",
                                                part.end(),
                                                "relationshipId",
                                                part.relationshipId(),
                                                "customerName",
                                                part.customerName(),
                                                "amount",
                                                part.amount()))
                        .toList());
        return value;
    }

    public record Decision(Long version, String reason) {}

    public record Detail(
            ReceivableChangeEntity record,
            JSONObject input,
            JSONObject preview,
            ReceivableView before) {}
}
