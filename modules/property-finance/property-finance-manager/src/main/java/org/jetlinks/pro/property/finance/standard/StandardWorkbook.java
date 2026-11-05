package org.jetlinks.pro.property.finance.standard;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** 导出完整筛选结果，所有金额保持文本以保留超过Excel精度的整数位。 */
public final class StandardWorkbook {
    private StandardWorkbook() {}

    public static byte[] write(List<StandardView> standards) throws Exception {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("收费标准");
            String[] titles = {
                "标准编码", "标准名称", "收费科目", "计费方式", "计费公式", "单价", "计算精度", "尾数处理", "结算方式", "生效开始",
                "生效结束", "有效状态", "税目", "开票公司", "备注", "启用逾期收费", "逾期计算方式", "逾期金额或比例", "逾期周期", "逾期基数"
            };
            var header = sheet.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
                sheet.setColumnWidth(i, 24 * 256);
            }
            int index = 1;
            for (var standard : standards) {
                var row = sheet.createRow(index++);
                Object[] values = {
                    standard.getStandardCode(),
                    standard.getStandardName(),
                    standard.getSubjectName(),
                    label("billingMethods", standard.getBillingMethod()),
                    standard.getFormulaDisplay(),
                    standard.getPrice().toPlainString(),
                    label("chargeRoundTypes", standard.getChargeRoundType()),
                    label("mantissas", standard.getMantissa()),
                    label("settlementMethods", standard.getSettlementMethod()),
                    standard.getExecuteDate(),
                    standard.getCancelDate(),
                    label("effectiveStatuses", standard.getEffectiveStatus()),
                    standard.getTaxSnapshot().get("goodsName"),
                    standard.getTaxSnapshot().get("companyName"),
                    standard.getRemark(),
                    Integer.valueOf(1).equals(standard.getIsDelay()) ? "是" : "否",
                    label("delayCalcForms", standard.getDelayCalcForm()),
                    standard.getRateAmount() == null
                            ? ""
                            : standard.getRateAmount().toPlainString(),
                    label("delayRoundTypes", standard.getDelayRoundType()),
                    label("delayBaseTypes", standard.getDelayBaseType())
                };
                for (int i = 0; i < values.length; i++)
                    row.createCell(i).setCellValue(values[i] == null ? "" : values[i].toString());
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    /** 导出复用表单选项，不把机器编码或数字当作业务名称。 */
    private static String label(String key, Object value) {
        if (value == null) return "";
        Object options = StandardOptions.enums().get(key);
        if (options instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> option && value.equals(option.get("value")))
                    return String.valueOf(option.get("text"));
            }
        }
        return value.toString();
    }
}
