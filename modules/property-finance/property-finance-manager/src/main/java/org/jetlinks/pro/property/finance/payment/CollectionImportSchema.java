package org.jetlinks.pro.property.finance.payment;

import java.util.*;

/** 收费人员填写业务名称；内部引用只由服务器匹配，不进入新模板。 */
public final class CollectionImportSchema {
    private CollectionImportSchema() {}

    public record Field(String key, String title, boolean required) {}

    public static List<Field> fields(String kind) {
        var result = new ArrayList<Field>();
        result.add(new Field("fullName", "房屋完整位置", true));
        result.add(new Field("customerName", "客户名称", true));
        result.add(new Field("subjectName", "收费科目", true));
        result.add(new Field("standardName", "收费标准", !"DEPOSIT".equals(kind)));
        if (!"DEPOSIT".equals(kind)) {
            result.add(new Field("startDate", "计费开始日期", true));
            result.add(new Field("endDate", "计费结束日期", true));
        }
        if ("RECEIVED".equals(kind)) result.add(new Field("arrears", "当前欠费金额（元）", false));
        result.add(new Field("amount", "RECEIVED".equals(kind) ? "本次收款金额（元）" : "收款金额（元）", true));
        if ("ADVANCE".equals(kind)) result.add(new Field("quantity", "数量", false));
        result.add(new Field("methodText", "支付方式", true));
        result.add(new Field("paidAt", "收款时间", true));
        result.add(new Field("payerName", "缴款人", false));
        result.add(new Field("externalNo", "原收款单号", false));
        result.add(new Field("remark", "备注", false));
        if ("RECEIVED".equals(kind)) {
            result.add(new Field("reductionAmount", "减免金额（元）", false));
            result.add(new Field("reductionReason", "减免原因", false));
        }
        return List.copyOf(result);
    }
}
