package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.entity.HandoverLineEntity;
import org.jetlinks.pro.property.finance.report.FinanceReportFile;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.*;

/** 预览和文件共用冻结交账行；现金净额和包含内部转存的业务金额分列。 */
@Service
@RequiredArgsConstructor
public class HandoverStatementService {
    private final HandoverRecords records;

    public record Row(
            String subjectName,
            String received,
            String currentReceived,
            String otherReceived,
            String advanceReceived,
            String advanceRefund,
            String refund,
            String cashAmount,
            String internalAmount,
            String amount) {}

    public record Statement(HandoverRecords.Detail detail, List<Row> rows) {}

    public Mono<Statement> statement(String project, String id) {
        return records.detail(project, id)
                .map(
                        detail -> {
                            var grouped = new TreeMap<String, List<HandoverLineEntity>>();
                            detail.lines()
                                    .forEach(
                                            line ->
                                                    grouped.computeIfAbsent(
                                                                    line.getSubjectName(),
                                                                    ignored -> new ArrayList<>())
                                                            .add(line));
                            return new Statement(
                                    detail,
                                    grouped.entrySet().stream()
                                            .map(
                                                    entry ->
                                                            summarize(
                                                                    entry.getKey(),
                                                                    entry.getValue()))
                                            .toList());
                        });
    }

    private static Row summarize(String subject, List<HandoverLineEntity> lines) {
        var zero = BigDecimal.ZERO.setScale(2);
        var income = zero;
        var refund = zero;
        var cash = zero;
        var amount = zero;
        var current = zero;
        var other = zero;
        var advance = zero;
        var advanceRefund = zero;
        for (var line : lines) {
            amount = amount.add(line.getAmount());
            cash = cash.add(line.getCashAmount());
            if (line.getCashAmount().signum() < 0) {
                refund = refund.add(line.getCashAmount());
                if ("FUND".equals(line.getSourceType()))
                    advanceRefund = advanceRefund.add(line.getCashAmount());
            } else {
                income = income.add(line.getCashAmount());
                if ("FUND".equals(line.getSourceType()))
                    advance = advance.add(line.getCashAmount());
                else {
                    // 非本期含跨期和历史缺失费用期间，与旧实收统计口径一致。
                    boolean inPeriod =
                            line.getCalcStartDate() != null
                                    && line.getCalcEndDate() != null
                                    && line.getOccurredAt()
                                                    .substring(0, 10)
                                                    .compareTo(line.getCalcStartDate())
                                            >= 0
                                    && line.getOccurredAt()
                                                    .substring(0, 10)
                                                    .compareTo(line.getCalcEndDate())
                                            <= 0;
                    if (inPeriod) current = current.add(line.getCashAmount());
                    else other = other.add(line.getCashAmount());
                }
            }
        }
        return new Row(
                subject,
                income.toPlainString(),
                current.toPlainString(),
                other.toPlainString(),
                advance.toPlainString(),
                advanceRefund.toPlainString(),
                refund.toPlainString(),
                cash.toPlainString(),
                amount.subtract(cash).toPlainString(),
                amount.toPlainString());
    }

    public Mono<byte[]> exportDetails(
            String project, HandoverRecords.DetailQuery input, String format) {
        if (!Set.of("excel", "pdf").contains(format))
            return Mono.error(FinanceScope.error("handover_invalid", 400));
        return records.detailRows(project, input)
                .flatMap(
                        details ->
                                Mono.fromCallable(
                                                () -> {
                                                    var columns =
                                                            List.of(
                                                                    "type",
                                                                    "fullName",
                                                                    "customerName",
                                                                    "subjectName",
                                                                    "occurredAt",
                                                                    "methodText",
                                                                    "bankName",
                                                                    "amount",
                                                                    "cashAmount",
                                                                    "batchId");
                                                    var labels =
                                                            Map.of(
                                                                    "type",
                                                                    "业务类型",
                                                                    "fullName",
                                                                    "房屋",
                                                                    "customerName",
                                                                    "客户",
                                                                    "subjectName",
                                                                    "科目",
                                                                    "occurredAt",
                                                                    "收支日期",
                                                                    "methodText",
                                                                    "支付方式",
                                                                    "bankName",
                                                                    "银行",
                                                                    "amount",
                                                                    "业务金额",
                                                                    "cashAmount",
                                                                    "现金净额",
                                                                    "batchId",
                                                                    "交账批次");
                                                    var rows =
                                                            details.stream()
                                                                    .map(
                                                                            row -> {
                                                                                var line =
                                                                                        row.line();
                                                                                return Map.of(
                                                                                        "type",
                                                                                        row.type(),
                                                                                        "fullName",
                                                                                        text(
                                                                                                line
                                                                                                        .getFullName()),
                                                                                        "customerName",
                                                                                        text(
                                                                                                line
                                                                                                        .getCustomerName()),
                                                                                        "subjectName",
                                                                                        text(
                                                                                                line
                                                                                                        .getSubjectName()),
                                                                                        "occurredAt",
                                                                                        line
                                                                                                .getOccurredAt(),
                                                                                        "methodText",
                                                                                        text(
                                                                                                line
                                                                                                        .getMethodText()),
                                                                                        "bankName",
                                                                                        text(
                                                                                                line
                                                                                                        .getBankName()),
                                                                                        "amount",
                                                                                        line.getAmount()
                                                                                                .toPlainString(),
                                                                                        "cashAmount",
                                                                                        line.getCashAmount()
                                                                                                .toPlainString(),
                                                                                        "batchId",
                                                                                        text(
                                                                                                row
                                                                                                        .batchId()));
                                                                            })
                                                                    .toList();
                                                    var amount =
                                                            details.stream()
                                                                    .map(
                                                                            row ->
                                                                                    row.line()
                                                                                            .getAmount())
                                                                    .reduce(
                                                                            BigDecimal.ZERO
                                                                                    .setScale(2),
                                                                            BigDecimal::add);
                                                    var cash =
                                                            details.stream()
                                                                    .map(
                                                                            row ->
                                                                                    row.line()
                                                                                            .getCashAmount())
                                                                    .reduce(
                                                                            BigDecimal.ZERO
                                                                                    .setScale(2),
                                                                            BigDecimal::add);
                                                    return FinanceReportFile.write(
                                                            new FinanceReportFile.Data(
                                                                    "交账明细",
                                                                    "原资金事实；交账不重复产生收入",
                                                                    "合计",
                                                                    columns,
                                                                    labels,
                                                                    Set.of("amount", "cashAmount"),
                                                                    rows,
                                                                    Map.of(
                                                                            "amount",
                                                                            amount.toPlainString(),
                                                                            "cashAmount",
                                                                            cash.toPlainString())),
                                                            format);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public Mono<byte[]> export(String project, String id, String format) {
        if (!Set.of("excel", "pdf").contains(format))
            return Mono.error(FinanceScope.error("handover_invalid", 400));
        return statement(project, id)
                .flatMap(
                        statement ->
                                Mono.fromCallable(
                                                () -> {
                                                    var columns =
                                                            List.of(
                                                                    "subjectName",
                                                                    "currentReceived",
                                                                    "otherReceived",
                                                                    "advanceReceived",
                                                                    "advanceRefund",
                                                                    "refund",
                                                                    "cashAmount",
                                                                    "internalAmount",
                                                                    "amount");
                                                    var labels =
                                                            Map.of(
                                                                    "subjectName",
                                                                    "科目",
                                                                    "currentReceived",
                                                                    "本期实收",
                                                                    "otherReceived",
                                                                    "非本期实收",
                                                                    "advanceReceived",
                                                                    "资金存入",
                                                                    "advanceRefund",
                                                                    "资金退还",
                                                                    "refund",
                                                                    "现金退款合计",
                                                                    "cashAmount",
                                                                    "现金净额",
                                                                    "internalAmount",
                                                                    "非现金结转",
                                                                    "amount",
                                                                    "业务金额");
                                                    var rows =
                                                            statement.rows().stream()
                                                                    .map(
                                                                            row ->
                                                                                    Map.of(
                                                                                            "subjectName",
                                                                                            row
                                                                                                    .subjectName(),
                                                                                            "currentReceived",
                                                                                            row
                                                                                                    .currentReceived(),
                                                                                            "otherReceived",
                                                                                            row
                                                                                                    .otherReceived(),
                                                                                            "advanceReceived",
                                                                                            row
                                                                                                    .advanceReceived(),
                                                                                            "advanceRefund",
                                                                                            row
                                                                                                    .advanceRefund(),
                                                                                            "refund",
                                                                                            row
                                                                                                    .refund(),
                                                                                            "cashAmount",
                                                                                            row
                                                                                                    .cashAmount(),
                                                                                            "internalAmount",
                                                                                            row
                                                                                                    .internalAmount(),
                                                                                            "amount",
                                                                                            row
                                                                                                    .amount()))
                                                                    .toList();
                                                    var batch = statement.detail().batch();
                                                    return FinanceReportFile.write(
                                                            new FinanceReportFile.Data(
                                                                    "交账单",
                                                                    batch.getHandoverNo()
                                                                            + " / "
                                                                            + batch.getClosingDate()
                                                                            + " / "
                                                                            + batch.getSubmittedBy()
                                                                            + "；资金存入/退还包含预收和押金；现金退款合计包含资金退还，不能重复相加；现金净额不包含内部结转。",
                                                                    "合计",
                                                                    columns,
                                                                    labels,
                                                                    new HashSet<>(
                                                                            columns.subList(
                                                                                    1,
                                                                                    columns
                                                                                            .size())),
                                                                    rows,
                                                                    Map.of(
                                                                            "cashAmount",
                                                                            batch.getCashAmount()
                                                                                    .toPlainString(),
                                                                            "amount",
                                                                            batch.getAmount()
                                                                                    .toPlainString())),
                                                            format);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
