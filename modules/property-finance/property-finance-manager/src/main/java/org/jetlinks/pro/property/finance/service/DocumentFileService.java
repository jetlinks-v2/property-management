package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.property.finance.document.DocumentLedgerRow;
import org.jetlinks.pro.property.finance.report.FinanceReportFile;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/** 票据台账与模拟票面共用授权后的只读快照，文件渲染不占用响应式数据库线程。 */
@Service
@RequiredArgsConstructor
public class DocumentFileService {
    private final DocumentRecordService records;

    public Mono<byte[]> excel(String project, String type, QueryParamEntity query) {
        return records.exportData(project, type, query)
                .zipWith(LocaleUtils.currentReactive().defaultIfEmpty(Locale.SIMPLIFIED_CHINESE))
                .flatMap(
                        pair ->
                                Mono.fromCallable(() -> excelFile(pair.getT1(), type, pair.getT2()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static byte[] excelFile(List<DocumentLedgerRow> rows, String type, Locale locale) {

        var columns =
                List.of(
                        "number",
                        "status",
                        "scene",
                        "source",
                        "house",
                        "customer",
                        "buyer",
                        "company",
                        "subject",
                        "period",
                        "payment",
                        "paid",
                        "issuePayment",
                        "issuePaid",
                        "amount",
                        "tax",
                        "net",
                        "date",
                        "operator",
                        "remark",
                        "standard",
                        "goods",
                        "rate",
                        "method",
                        "difference",
                        "titleType",
                        "rule");
        var labels =
                List.of(
                        "票据号", "票据状态", "开具场景", "来源类型", "房屋", "收费对象", "购方抬头", "开具公司", "科目", "计费期间",
                        "当前收款状态", "当前已收金额", "开具时收款状态", "开具时已收金额", "开具金额", "税额", "不含税金额", "开具时间",
                        "开具人", "备注", "收费标准", "税目", "税率", "征收方式", "扣除金额", "抬头类型", "开具规则");
        var names = new LinkedHashMap<String, String>();
        for (int i = 0; i < columns.size(); i++)
            names.put(columns.get(i), translate("column." + columns.get(i), labels.get(i), locale));
        var data = rows.stream().map(row -> values(row, locale)).toList();
        return FinanceReportFile.write(
                new FinanceReportFile.Data(
                        translate(type + "Title", "INVOICE".equals(type) ? "发票台账" : "收据台账", locale),
                        translate(
                                type + "Scope",
                                "INVOICE".equals(type)
                                        ? "不作为税务发票使用。实时收款与开具快照分别列示。"
                                        : "收款状态与收据状态分别列示；开具收据不代表已经收款。",
                                locale),
                        translate("count", "票据数", locale) + ": " + rows.size(),
                        columns,
                        names,
                        Set.of("paid", "issuePaid", "amount", "tax", "net"),
                        data,
                        Map.of()),
                "excel");
    }

    public Mono<byte[]> invoicePdf(String project, String id) {
        return records.detail(project, "INVOICE", id)
                .zipWith(LocaleUtils.currentReactive().defaultIfEmpty(Locale.SIMPLIFIED_CHINESE))
                .flatMap(
                        pair ->
                                Mono.fromCallable(
                                                () ->
                                                        org.jetlinks.pro.property.finance.document
                                                                .InvoicePdfRenderer.render(
                                                                pair.getT1(), pair.getT2()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static Map<String, String> values(DocumentLedgerRow r, Locale locale) {
        var v = new LinkedHashMap<String, String>();
        v.put("number", r.getDocumentNumber());
        v.put("status", label(r.getStatus(), locale));
        v.put("scene", label(r.getIssueScene(), locale));
        v.put("source", label(r.getSourceType(), locale));
        v.put("house", r.getFullName());
        v.put("customer", r.getCustomerName());
        v.put("buyer", r.getBuyerName());
        v.put("company", r.getCompanyName());
        v.put("subject", r.getSubjectName());
        v.put("period", r.getPeriod());
        v.put("payment", label(r.getPaymentStatus(), locale));
        v.put("paid", text(r.getPaidAmount()));
        v.put("issuePayment", label(r.getIssuePaymentStatus(), locale));
        v.put("issuePaid", r.getIssuePaidAmount());
        v.put("amount", r.getAmount().toPlainString());
        v.put("tax", r.getTaxAmount().toPlainString());
        v.put("net", r.getNetAmount().toPlainString());
        v.put("date", r.getIssuedAt());
        v.put("operator", r.getIssuerName());
        v.put("remark", text(r.getRemark()));
        v.put("standard", join(r, l -> text(l.standardName())));
        v.put("goods", join(r, l -> text(l.goodsName())));
        v.put("rate", join(r, l -> text(l.taxRate())));
        v.put("method", join(r, l -> label(l.collectionMethod(), locale)));
        v.put("difference", join(r, l -> text(l.differenceAmount())));
        v.put("titleType", label(r.getBuyerTitleType(), locale));
        v.put(
                "rule",
                label(r.getTimeLimitType(), locale)
                        + " / "
                        + Objects.toString(r.getLimitDays(), "—")
                        + " / "
                        + Objects.toString(r.getMaxReopenTimes(), "—"));
        return v;
    }

    private static String join(
            DocumentLedgerRow row,
            java.util.function.Function<DocumentPreparationService.Line, String> value) {
        return row.getSources().stream()
                .map(value)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static String translate(String key, String fallback, Locale locale) {
        return LocaleUtils.resolveMessage("message.finance.document.file." + key, locale, fallback);
    }

    private static String text(String value) {
        return Objects.toString(value, "");
    }

    private static String label(String value, Locale locale) {
        if (value == null) return translate("value.UNKNOWN", "未知", locale);
        var fallback =
                switch (value) {
                    case "SIMULATED_ISSUED" -> "已开具";
                    case "SIMULATED_REVERSED" -> "已红冲";
                    case "SIMULATED_RED" -> "红票";
                    case "ISSUED" -> "已开具";
                    case "VOIDED" -> "已作废";
                    case "BEFORE_PAYMENT" -> "付款前";
                    case "AFTER_PAYMENT" -> "付款后";
                    case "MIXED" -> "混合";
                    case "RECEIVABLE" -> "应收款";
                    case "TEMP_PAYMENT" -> "临时收款";
                    case "FUND_TOPUP" -> "预收/押金原存入";
                    case "UNPAID" -> "未收款";
                    case "PARTIAL" -> "部分收款";
                    case "PAID" -> "已收款";
                    case "UNKNOWN" -> "未知";
                    case "FULL" -> "全额征收";
                    case "DIFFERENTIAL" -> "差额征收";
                    case "ENTERPRISE" -> "企业";
                    case "PERSONAL" -> "个人";
                    case "NATURAL_YEAR" -> "自然年";
                    case "CUSTOM_DAYS" -> "付款后指定天数";
                    default -> value;
                };
        return translate("value." + value, fallback, locale);
    }
}
