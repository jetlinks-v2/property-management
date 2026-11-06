package org.jetlinks.pro.property.finance.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.report.*;

import reactor.core.publisher.*;

import java.util.*;
import java.util.function.Supplier;

/** 分组与明细串行共享调用方只读事务，不在一条R2DBC连接并行查询。 */
final class ReportWorkbookLoader {
    private ReportWorkbookLoader() {}

    static Mono<ReportWorkbook> load(
            ReportExportInput input,
            List<String> projects,
            FinanceScope scope,
            Supplier<Mono<FinanceReportFile.Data>> current,
            Supplier<Mono<FinanceReportFile.Data>> detail,
            Locale locale) {
        input.validateExport();
        var values = JSON.parseObject(JSON.toJSONString(input));
        var loaders = new ArrayList<Mono<FinanceReportFile.Data>>();
        if (!"DETAIL".equals(input.getExportMode())) loaders.add(Mono.defer(current));
        // 明细视图的“汇总与明细”只有一个数据集，不重复写出相同工作表。
        if (!"CURRENT".equals(input.getExportMode())
                && !("BOTH".equals(input.getExportMode())
                        && "DETAIL".equals(values.getString("groupBy"))))
            loaders.add(Mono.defer(detail));
        return Flux.concat(loaders)
                .collectList()
                .flatMap(
                        sheets ->
                                Flux.fromIterable(projects)
                                        .concatMap(
                                                id -> scope.project(id, CrudAssetPermission.read))
                                        .map(project -> project.name())
                                        .collectList()
                                        .map(
                                                names ->
                                                        new ReportWorkbook(
                                                                sheets,
                                                                conditions(
                                                                        values, names, locale))));
    }

    private static Map<String, String> conditions(
            JSONObject values, List<String> projects, Locale locale) {
        boolean zh = "zh".equals(locale.getLanguage());
        var conditions = new LinkedHashMap<String, String>();
        conditions.put(zh ? "项目范围" : "Projects", String.join("、", projects));
        String[][] fields = {
            {"groupBy", "报表视图", "View"},
            {"exportMode", "导出范围", "Export range"},
            {"receivableDateStart", "应收日期起", "Receivable from"},
            {"receivableDateEnd", "应收日期止", "Receivable to"},
            {"dueDateStart", "到期日起", "Due from"},
            {"dueDateEnd", "到期日止", "Due to"},
            {"receivedDateStart", "收支日期起", "Received from"},
            {"receivedDateEnd", "收支日期止", "Received to"},
            {"invoiceDateStart", "开票日期起", "Invoice from"},
            {"invoiceDateEnd", "开票日期止", "Invoice to"},
            {"customerName", "客户", "Customer"},
            {"buyerName", "购方抬头", "Buyer"},
            {"fullName", "房屋", "Space"},
            {"collectorName", "收银员", "Collector"},
            {"paymentNo", "收支单号", "Payment number"},
            {"receivableNo", "应收单号", "Bill number"},
            {"invoiceNo", "发票号码", "Invoice number"},
            {"collectionStatus", "收款状态", "Collection status"},
            {"handoverState", "交账状态", "Handover status"},
            {"overdueOnly", "仅逾期未收", "Overdue only"},
            {"sourceType", "收费来源", "Source"},
            {"invoiceStatus", "开票状态", "Invoice coverage"},
            {"ticketColor", "蓝红类型", "Invoice color"},
            {"ticketStatus", "发票状态", "Invoice status"},
            {"invoiceType", "发票类型", "Invoice type"},
            {"paymentMethods", "支付方式", "Payment methods"},
            {"sourceTypes", "收款类型", "Source types"},
            {"subjectGroups", "科目范围", "Subjects"},
            {"methodGroups", "支付方式范围", "Methods"},
            {"subjectIds", "收费科目", "Subjects"},
            {"spaceIds", "房屋范围", "Spaces"}
        };
        for (var field : fields) {
            Object value = values.get(field[0]);
            if (value == null || value instanceof Collection<?> c && c.isEmpty()) continue;
            String text;
            if (value instanceof Collection<?> c)
                text =
                        c.stream()
                                .map(
                                        v ->
                                                v instanceof Map<?, ?> m
                                                        ? Objects.toString(m.get("label"), "")
                                                        : display(Objects.toString(v), zh))
                                .collect(java.util.stream.Collectors.joining("、"));
            else text = display(value.toString(), zh);
            // 编码仅作为查询标识，不将内部账号ID当成收银员姓名展示。
            if (field[0].endsWith("Ids"))
                text =
                        (zh ? "已选择 " : "Selected ")
                                + ((Collection<?>) value).size()
                                + (zh ? " 项" : " items");
            conditions.put(field[zh ? 1 : 2], text);
        }
        conditions.put(
                zh ? "生成时间" : "Generated",
                java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return conditions;
    }

    private static String display(String value, boolean zh) {
        String[][] labels = {
            {"DETAIL", "业务明细", "Details"},
            {"PROJECT", "项目汇总", "Projects"},
            {"SUBJECT", "科目汇总", "Subjects"},
            {"METHOD", "支付方式汇总", "Methods"},
            {"SOURCE", "收款类型汇总", "Sources"},
            {"COLLECTOR", "收银员汇总", "Collectors"},
            {"CURRENT", "当前视图", "Current view"},
            {"BOTH", "汇总与明细", "Summary and details"},
            {"UNHANDED", "已收未交账", "Not handed over"},
            {"PENDING", "已收未入账", "Pending posting"},
            {"APPROVED", "已入账", "Posted"},
            {"UNPAID", "未收款", "Unpaid"},
            {"PARTIAL", "部分", "Partial"},
            {"PAID", "已收款", "Paid"},
            {"RECEIVABLE", "应收收款", "Receivable"},
            {"TEMPORARY", "临时收款", "Temporary payment"},
            {"TEMP_PAYMENT", "临时收款", "Temporary payment"},
            {"REFUND", "收款退款", "Refund"},
            {"TEMPORARY_REFUND", "临时退款", "Temporary refund"},
            {"ADVANCE", "预收款", "Advance"},
            {"DEPOSIT", "押金", "Deposit"},
            {"ADVANCE_REFUND", "预收退还", "Advance refund"},
            {"DEPOSIT_REFUND", "押金退还", "Deposit refund"},
            {"FUND_TOPUP", "预收原存入", "Advance top-up"},
            {"UNISSUED", "未开票", "Unissued"},
            {"INVOICED", "已开票", "Invoiced"},
            {"REVERSED", "已红冲", "Reversed"},
            {"BLUE", "蓝票", "Blue invoice"},
            {"RED", "红票", "Red invoice"},
            {"SIMULATED_ISSUED", "已开具", "Issued"},
            {"SIMULATED_REVERSED", "已红冲", "Reversed"},
            {"SIMULATED_RED", "红字发票", "Red invoice"},
            {"1", "普通发票", "Ordinary invoice"},
            {"2", "专用发票", "Special invoice"},
            {"true", "是", "Yes"},
            {"false", "否", "No"},
            {"CASH", "现金", "Cash"},
            {"BANK_TRANSFER", "银行转账", "Bank transfer"},
            {"WECHAT", "微信", "WeChat"},
            {"ALIPAY", "支付宝", "Alipay"},
            {"POS", "刷卡", "POS"}
        };
        for (var item : labels) if (item[0].equals(value)) return item[zh ? 1 : 2];
        return value;
    }
}
