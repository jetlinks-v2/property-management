package org.jetlinks.pro.property.finance.receivable;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** 与现有财务导出一致：金额写文本，避免Excel的15位有效数字截断。 */
public final class ReceivableWorkbook {
    private ReceivableWorkbook() {}

    public static byte[] write(List<ReceivableEntity> rows) throws Exception {
        try (var book = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("应收款");
            String[] titles = {
                "账单编号", "房屋", "收费科目", "收费标准", "收费客户", "账期开始", "账期结束", "计费开始", "计费结束", "到期日", "账期状态",
                "冻结状态", "账期应收", "已计提本金", "调整金额", "考核金额", "临时增派金额", "本金减免", "应计逾期费用", "逾期减免",
                "实际逾期费用", "实际应收", "已收金额", "欠费金额", "计算截至日"
            };
            var header = sheet.createRow(0);
            var style = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int i = 0; i < titles.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(titles[i]);
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, (i == 1 ? 44 : 22) * 256);
            }
            int index = 1;
            for (var row : rows) {
                Object[] values = {
                    row.getId(),
                    row.getFullName(),
                    row.getSubjectName(),
                    row.getStandardName(),
                    row.getCustomerName(),
                    row.getPeriodStart(),
                    row.getPeriodEnd(),
                    row.getCalcStartDate(),
                    row.getCalcEndDate(),
                    row.getDueDate(),
                    "CLOSED".equals(row.getPeriodStatus()) ? "已关闭" : "开放中",
                    Boolean.TRUE.equals(row.getFrozen()) ? "已冻结" : "未冻结",
                    row.getContractReceivableAmount(),
                    row.getAccruedReceivableAmount(),
                    row.getAdjustmentAmount(),
                    row.getAssessmentAmount(),
                    row.getTemporaryDispatchAmount(),
                    row.getFeeReductionAmount(),
                    row.getAccruedLateFeeAmount(),
                    row.getLateFeeReductionAmount(),
                    row.getFinalLateFeeAmount(),
                    row.getActualReceivableAmount(),
                    row.getPaidAmount(),
                    row.getArrears(),
                    row.getCalculatedAsOfDate()
                };
                var target = sheet.createRow(index++);
                for (int i = 0; i < values.length; i++)
                    target.createCell(i)
                            .setCellValue(values[i] == null ? "" : values[i].toString());
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(
                    new org.apache.poi.ss.util.CellRangeAddress(
                            0, rows.size(), 0, titles.length - 1));
            book.write(output);
            return output.toByteArray();
        }
    }
}
