package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
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

/** 调整预览无写入；正式提交锁定原账、重算并比对预览，账单与审计同事务发布，无审批状态。 */
@Service
@RequiredArgsConstructor
public class ReceivableAdjustmentService {
    private final FinanceScope scope;
    private final AdjustmentRules rules;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    public Mono<AdjustmentRules.Result> preview(
            String project, String id, AdjustmentInput input, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(row(project, id, false))
                .map(row -> rules.calculate(row, input, today));
    }

    @Transactional
    public Mono<ReceivableView> submit(
            String project, String id, SubmitInput request, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, true)
                                        .flatMap(
                                                before ->
                                                        publish(
                                                                project, before, request, today,
                                                                auth)))
                .as(MonoTracer.create("/property/finance/receivable/adjust"));
    }

    private Mono<ReceivableView> publish(
            String project,
            ReceivableEntity before,
            SubmitInput request,
            LocalDate today,
            org.hswebframework.web.authorization.Authentication auth) {
        return Mono.defer(
                () -> {
                    if (request == null || request.input() == null || request.token() == null)
                        return Mono.error(error("adjustment_preview_expired", 409));
                    var result = rules.calculate(before, request.input(), today);
                    if (!Objects.equals(request.token(), result.token()))
                        return Mono.error(error("adjustment_preview_expired", 409));
                    return persist(project, before, request.input(), result, auth);
                });
    }

    /** 仅内部结转应用服务调用，锁和审计沿用普通调整，不能通过HTTP请求启用此模式。 */
    @Transactional
    Mono<ReceivableView> reduceForCarry(
            String project, String id, AdjustmentInput input, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, true)
                                        .flatMap(
                                                before ->
                                                        persist(
                                                                project,
                                                                before,
                                                                input,
                                                                rules.calculateReduction(
                                                                        before, input, today),
                                                                auth)));
    }

    private Mono<ReceivableView> persist(
            String project,
            ReceivableEntity before,
            AdjustmentInput input,
            AdjustmentRules.Result result,
            org.hswebframework.web.authorization.Authentication auth) {
        var after = result.after();
        var log = log(project, before, after, input, result, auth);
        return audit(bills.createUpdate(), auth, before.getVersion())
                .set("adjustmentAmount", after.getAdjustmentAmount())
                .set("assessmentAmount", after.getAssessmentAmount())
                .set("temporaryDispatchAmount", after.getTemporaryDispatchAmount())
                .set("feeReductionAmount", after.getFeeReductionAmount())
                .set("lateFeeReductionAmount", after.getLateFeeReductionAmount())
                .set("contractReceivableAmount", after.getContractReceivableAmount())
                .set("accruedReceivableAmount", after.getAccruedReceivableAmount())
                .set("accruedLateFeeAmount", after.getAccruedLateFeeAmount())
                .set("finalLateFeeAmount", after.getFinalLateFeeAmount())
                .set("actualReceivableAmount", after.getActualReceivableAmount())
                .set("arrears", after.getArrears())
                .set("accruedAsOfDate", after.getAccruedAsOfDate())
                .set("calculatedAsOfDate", after.getCalculatedAsOfDate())
                .set("periodStatus", after.getPeriodStatus())
                .where("id", before.getId())
                .and("projectId", project)
                .and("version", before.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(logs.insert(log))
                .thenReturn(ReceivableView.of(after, false));
    }

    private static ReceivableLogEntity log(
            String project,
            ReceivableEntity before,
            ReceivableEntity after,
            AdjustmentInput input,
            AdjustmentRules.Result result,
            org.hswebframework.web.authorization.Authentication auth) {
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(project);
        log.setSpaceId(before.getSpaceId());
        log.setReceivableId(before.getId());
        log.setOperation("ADJUST");
        log.setReason(input.getReason().trim());
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(auth.getUser().getUsername());
        log.setCreateTime(System.currentTimeMillis());
        log.setBeforeVersion(before.getVersion());
        log.setBeforeJson(ReceivableService.auditSnapshot(before));
        after.setVersion(before.getVersion() + 1);
        after.setModifyTime(log.getCreateTime());
        after.setModifierId(log.getOperatorId());
        after.setModifierName(log.getOperatorName());
        log.setAfterVersion(after.getVersion());
        log.setAfterJson(ReceivableService.auditSnapshot(after));
        var preview = new LinkedHashMap<String, Object>();
        preview.put("delta", result.delta().toPlainString());
        preview.put("formula", result.formula());
        preview.put("calculationAsOfDate", result.calculationAsOfDate().toString());
        preview.put("businessDate", result.businessDate().toString());
        preview.put(
                "referenceBefore",
                JSON.parseObject(ReceivableService.auditSnapshot(result.referenceBefore())));
        preview.put(
                "referenceAfter",
                JSON.parseObject(ReceivableService.auditSnapshot(result.referenceAfter())));
        preview.put(
                "effectiveBefore",
                JSON.parseObject(ReceivableService.auditSnapshot(result.effectiveBefore())));
        preview.put(
                "effectiveAfter",
                JSON.parseObject(ReceivableService.auditSnapshot(result.effectiveAfter())));
        log.setDetailJson(JSON.toJSONString(Map.of("input", input, "preview", preview)));
        return log;
    }

    private Mono<ReceivableEntity> row(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return bills.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                row.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .thenReturn(row));
    }

    public record SubmitInput(AdjustmentInput input, String token) {}
}
