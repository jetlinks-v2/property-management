package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.HistoricalWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;

/** 待审明细只修改暂存数据；完整重新匹配和校验后更新双版本，审核后才发布正式账单。 */
@Service
@RequiredArgsConstructor
public class HistoricalImportEditService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periods;
    private final HistoricalReceivableService imports;
    private final ReactiveRepository<ReceivableImportEntity, String> records;
    private final ReactiveRepository<ReceivableImportLineEntity, String> drafts;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    @Transactional
    public Mono<Result> edit(String project, String recordId, String lineId, Input input) {
        if (input == null || input.version() == null || input.lineVersion() == null)
            return Mono.error(error("historical_review_invalid", 400));
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                periods.lock(project)
                                        .flatMap(
                                                control ->
                                                        record(project, recordId)
                                                                .flatMap(
                                                                        record ->
                                                                                editRecord(
                                                                                        project,
                                                                                        record,
                                                                                        lineId,
                                                                                        input,
                                                                                        control,
                                                                                        auth))))
                .as(MonoTracer.create("/property/finance/receivable/import-edit"));
    }

    private Mono<Result> editRecord(
            String project,
            ReceivableImportEntity record,
            String lineId,
            Input input,
            FinancialPeriodControlEntity control,
            Authentication auth) {
        if (!"PENDING".equals(record.getStatus())
                || !Objects.equals(input.version(), record.getVersion()))
            return Mono.error(error("version_conflict", 409));
        return draft(project, record.getId(), lineId)
                .flatMap(row -> editDraft(project, record, row, input, control, auth));
    }

    private Mono<Result> editDraft(
            String project,
            ReceivableImportEntity record,
            ReceivableImportLineEntity draft,
            Input input,
            FinancialPeriodControlEntity control,
            Authentication auth) {
        if (!"PENDING".equals(draft.getStatus())
                || !Objects.equals(input.lineVersion(), draft.getVersion()))
            return Mono.error(error("version_conflict", 409));
        var line = input.line(draft.getLineNumber());
        var original = JSON.parseObject(draft.getPayloadJson(), ReceivableEntity.class);
        return AssetsHolder.assertPermission(
                        AssetType.of("space"), draft.getSpaceId(), CrudAssetPermission.save)
                .then(periods.assertOpen(control, LocalDate.now()))
                .then(periods.assertOpen(control, LocalDate.parse(original.getCalcStartDate())))
                .then(periods.assertOpen(control, line.start()))
                .then(imports.reprepare(project, record, line, auth))
                .flatMap(bill -> save(project, record, draft, bill, auth));
    }

    private Mono<Result> save(
            String project,
            ReceivableImportEntity record,
            ReceivableImportLineEntity draft,
            ReceivableEntity bill,
            Authentication auth) {
        // 不允许修改后在同一批次内产生另一条相同历史应收，包括已审核行。
        return drafts.createQuery()
                .where("projectId", project)
                .and("importId", record.getId())
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            for (var row : rows) {
                                if (row.getId().equals(draft.getId())) continue;
                                var other =
                                        JSON.parseObject(
                                                row.getPayloadJson(), ReceivableEntity.class);
                                if (Objects.equals(bill.getBillKey(), other.getBillKey()))
                                    return Mono.error(
                                            HistoricalWorkbook.rowError(
                                                    draft.getLineNumber(),
                                                    "房屋/客户/科目/标准/日期",
                                                    "historical_duplicate_file"));
                            }
                            var before =
                                    JSON.parseObject(
                                            draft.getPayloadJson(), ReceivableEntity.class);
                            bill.setId(draft.getId());
                            bill.setCreateTime(before.getCreateTime());
                            var event = HistoricalReceivableService.log(bill, auth);
                            event.setOperation("IMPORT_EDIT");
                            event.setReason("修改待审历史应收");
                            event.setBeforeVersion(draft.getVersion());
                            event.setAfterVersion(draft.getVersion() + 1);
                            event.setBeforeJson(ReceivableService.auditSnapshot(before));
                            return audit(drafts.createUpdate(), auth, draft.getVersion())
                                    .set("payloadJson", JSON.toJSONString(bill))
                                    .set("spaceId", bill.getSpaceId())
                                    .set("fullName", bill.getFullName())
                                    .set("customerName", bill.getCustomerName())
                                    .where("id", draft.getId())
                                    .and("projectId", project)
                                    .and("importId", record.getId())
                                    .and("version", draft.getVersion())
                                    .and("status", "PENDING")
                                    .execute()
                                    .flatMap(SubjectService::changed)
                                    .then(
                                            audit(records.createUpdate(), auth, record.getVersion())
                                                    .where("id", record.getId())
                                                    .and("projectId", project)
                                                    .and("version", record.getVersion())
                                                    .and("status", "PENDING")
                                                    .execute()
                                                    .flatMap(SubjectService::changed))
                                    .then(logs.insert(event))
                                    .then(
                                            Mono.zip(
                                                    records.findById(record.getId()),
                                                    drafts.findById(draft.getId())))
                                    .map(
                                            pair ->
                                                    new Result(
                                                            pair.getT1(),
                                                            HistoricalImportAuditService.LineView
                                                                    .of(pair.getT2())));
                        });
    }

    private Mono<ReceivableImportEntity> record(String project, String id) {
        var locking = new QueryParamEntity();
        locking.setForUpdate(true);
        return records.createQuery()
                .setParam(locking)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("historical_review_not_found", 404)));
    }

    private Mono<ReceivableImportLineEntity> draft(String project, String recordId, String id) {
        var locking = new QueryParamEntity();
        locking.setForUpdate(true);
        return drafts.createQuery()
                .setParam(locking)
                .where("id", id)
                .and("projectId", project)
                .and("importId", recordId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("historical_review_not_found", 404)));
    }

    public record Result(
            ReceivableImportEntity record, HistoricalImportAuditService.LineView line) {}

    public record Input(
            Long version,
            Long lineVersion,
            String fullName,
            String customerName,
            String subjectName,
            String standardName,
            String calcStartDate,
            String calcEndDate,
            String accountPeriod,
            String dueDate,
            BigDecimal contractReceivableAmount,
            BigDecimal feeReductionAmount,
            BigDecimal actualReceivableAmount,
            String remark) {
        HistoricalWorkbook.Line line(int row) {
            var start = date(calcStartDate, row, "计费开始日期");
            var end = date(calcEndDate, row, "计费结束日期");
            var due = date(dueDate, row, "应收日期");
            if (start.isAfter(end))
                throw HistoricalWorkbook.rowError(row, "计费结束日期", "historical_dates_invalid");
            var period = required(accountPeriod, row, "应收账期");
            if (!period.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
                throw HistoricalWorkbook.rowError(row, "应收账期", "historical_period_invalid");
            var original = money(contractReceivableAmount, row, "原应收");
            var reduction = money(feeReductionAmount, row, "减免金额");
            var actual = money(actualReceivableAmount, row, "实际应收");
            if (original.subtract(reduction).compareTo(actual) != 0)
                throw HistoricalWorkbook.rowError(row, "实际应收", "historical_amount_mismatch");
            if (text(remark).length() > 1000)
                throw HistoricalWorkbook.rowError(row, "备注", "historical_text_limit");
            return new HistoricalWorkbook.Line(
                    row,
                    "",
                    required(fullName, row, "房屋完整路径"),
                    required(customerName, row, "客户名称"),
                    required(subjectName, row, "收费科目"),
                    required(standardName, row, "收费标准"),
                    start,
                    end,
                    period,
                    due,
                    original,
                    reduction,
                    actual,
                    text(remark));
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }

        private static String required(String value, int row, String field) {
            if (text(value).isEmpty())
                throw HistoricalWorkbook.rowError(row, field, "historical_required");
            return text(value);
        }

        private static LocalDate date(String value, int row, String field) {
            var raw = required(value, row, field);
            try {
                return LocalDate.parse(
                        raw,
                        DateTimeFormatter.ofPattern("uuuu-MM-dd")
                                .withResolverStyle(ResolverStyle.STRICT));
            } catch (RuntimeException failure) {
                throw HistoricalWorkbook.rowError(row, field, "historical_date_invalid");
            }
        }

        private static BigDecimal money(BigDecimal value, int row, String field) {
            if (value == null
                    || value.signum() < 0
                    || value.scale() > 2
                    || value.precision() - value.scale() > 18)
                throw HistoricalWorkbook.rowError(row, field, "historical_money_invalid");
            return value.setScale(2);
        }
    }
}
