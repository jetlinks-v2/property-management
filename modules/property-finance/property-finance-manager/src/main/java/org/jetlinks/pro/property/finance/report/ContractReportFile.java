package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** 合同月报文件只读取已授权快照；金额以文本保持精度，复用财务统一安全排版。 */
@Component
public class ContractReportFile {
    private static final List<String> MONEY =
            List.of(
                    "openingReceivable",
                    "openingAdvance",
                    "currentReceivable",
                    "currentNetReceived",
                    "closingReceivable",
                    "closingAdvance");
    private static final List<String> FIELDS =
            List.of(
                    "code",
                    "name",
                    "customerName",
                    "fullName",
                    "startDate",
                    "endDate",
                    "leaseArea");
    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("title", "合同账单报表"),
                    Map.entry("summary", "全筛选范围合计"),
                    Map.entry(
                            "scope",
                            "项目：{0}；统计月份：{1}。按冻结合同账单金额分配，实收含余额冲抵、扣除退款；预收为合同期间差额，不是预存账户余额。"),
                    Map.entry("code", "合同编号"),
                    Map.entry("name", "合同名称"),
                    Map.entry("customerName", "客户"),
                    Map.entry("fullName", "房屋"),
                    Map.entry("startDate", "履约开始"),
                    Map.entry("endDate", "履约结束"),
                    Map.entry("leaseArea", "租赁面积（㎡）"),
                    Map.entry("openingReceivable", "期初欠费（元）"),
                    Map.entry("openingAdvance", "期初预收（元）"),
                    Map.entry("currentReceivable", "本月应收（元）"),
                    Map.entry("currentNetReceived", "本月净实收（元）"),
                    Map.entry("closingReceivable", "期末欠费（元）"),
                    Map.entry("closingAdvance", "期末预收（元）"));
    private static final Map<String, String> EN =
            Map.ofEntries(
                    Map.entry("title", "Contract billing report"),
                    Map.entry("summary", "Filtered total"),
                    Map.entry(
                            "scope",
                            "Project: {0}; month: {1}. Frozen contract bill allocation; receipts"
                                + " include balance offsets less refunds. Advance is the contract"
                                + " period difference, not the balance account."),
                    Map.entry("code", "Contract code"),
                    Map.entry("name", "Contract name"),
                    Map.entry("customerName", "Customer"),
                    Map.entry("fullName", "Space"),
                    Map.entry("startDate", "Contract start"),
                    Map.entry("endDate", "Contract end"),
                    Map.entry("leaseArea", "Lease area (sqm)"),
                    Map.entry("openingReceivable", "Opening receivable (CNY)"),
                    Map.entry("openingAdvance", "Opening advance (CNY)"),
                    Map.entry("currentReceivable", "Current receivable (CNY)"),
                    Map.entry("currentNetReceived", "Net received (CNY)"),
                    Map.entry("closingReceivable", "Closing receivable (CNY)"),
                    Map.entry("closingAdvance", "Closing advance (CNY)"));

    public byte[] write(ContractReportResult result, String format, Locale locale) {
        var columns = new ArrayList<>(FIELDS);
        columns.addAll(MONEY);
        var labels = new LinkedHashMap<String, String>();
        columns.forEach(c -> labels.put(c, label(c, locale)));
        String scope =
                java.text.MessageFormat.format(
                        label("scope", locale), result.projectName(), result.month());
        return FinanceReportFile.write(
                new FinanceReportFile.Data(
                        label("title", locale),
                        scope,
                        label("summary", locale),
                        columns,
                        labels,
                        Set.copyOf(MONEY),
                        result.page().getData().stream().map(ContractReportFile::values).toList(),
                        amounts(result.summary())),
                format);
    }

    private static Map<String, String> values(ContractReportResult.Row r) {
        var v = new HashMap<>(amounts(r.amounts()));
        v.put("code", r.code());
        v.put("name", r.name());
        v.put("customerName", r.customerName());
        v.put("fullName", r.fullName());
        v.put("startDate", r.startDate());
        v.put("endDate", r.endDate());
        v.put("leaseArea", r.leaseArea());
        return v;
    }

    private static Map<String, String> amounts(ContractReportResult.Amounts a) {
        var values = new LinkedHashMap<String, String>();
        MONEY.forEach(metric -> values.put(metric, a.value(metric)));
        return values;
    }

    private static String label(String key, Locale locale) {
        return LocaleUtils.resolveMessage(
                "message.finance.report.contract." + key,
                locale,
                ("zh".equals(locale.getLanguage()) ? ZH : EN).getOrDefault(key, key));
    }
}
