package org.jetlinks.pro.property.finance.receivable;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** 与现有财务导出一致：金额写文本，避免Excel的15位有效数字截断。 */
public final class ReceivableWorkbook {
    private ReceivableWorkbook() {}

    public static byte[] write(List<ReceivableView> rows) throws Exception {
        try (var book = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("应收款");
            String[] titles = {
                "应收单号", "收费周期", "项目名称", "房产简称", "收费对象", "收费科目", "收费标准", "计费开始日期", "计费结束日期", "合同应收",
                "截至今日应计", "实际应收", "收入（不含税）", "销项税额", "调整金额", "考核金额", "临时增派", "欠费金额", "已收金额", "缴款时间",
                "回款周期（天）", "逾期周期（天）", "账期状态", "回款状态", "支付状态", "单价", "面积/用量", "应计滞纳金", "滞纳金减免",
                "最终滞纳金", "费用减免", "应收账期", "应收日期", "被拆分", "是否删除", "是否锁定", "是否冻结", "账单来源", "任务名称",
                "创建时间", "备注", "审核状态", "计算截至日"
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
                    row.getBillNo(),
                    text(row.getPeriodStart()) + " 至 " + text(row.getPeriodEnd()),
                    row.getProjectName(),
                    row.getFullName(),
                    String.join("、", row.getCustomerNames()),
                    row.getSubjectName(),
                    row.getStandardName(),
                    row.getCalcStartDate(),
                    row.getCalcEndDate(),
                    row.getContractReceivableAmount(),
                    row.getAccruedReceivableAmount(),
                    row.getActualReceivableAmount(),
                    row.getIncomeExcludingTax(),
                    row.getOutputTaxAmount(),
                    row.getAdjustmentAmount(),
                    row.getAssessmentAmount(),
                    row.getTemporaryDispatchAmount(),
                    row.getArrears(),
                    row.getPaidAmount(),
                    row.getPaidDate(),
                    row.getCollectionDays(),
                    row.getOverdueDays(),
                    "CLOSED".equals(row.getPeriodStatus()) ? "已关闭" : "开放",
                    collection(row.getCollectionStatus()),
                    payment(row.getPayStatus()),
                    row.getPrice(),
                    row.getUsageValue(),
                    row.getAccruedLateFeeAmount(),
                    row.getLateFeeReductionAmount(),
                    row.getFinalLateFeeAmount(),
                    row.getFeeReductionAmount(),
                    row.getAccountPeriod() == null && row.getPeriodStart() != null
                            ? row.getPeriodStart().substring(0, 7)
                            : row.getAccountPeriod(),
                    row.getDueDate(),
                    yes(row.getSplit()),
                    yes(row.isDeletedFlag()),
                    yes(row.getChargeLocked()),
                    yes(row.getFrozen()),
                    source(row.getSourceType()),
                    row.getTaskName(),
                    row.getCreateTime() == null
                            ? null
                            : Instant.ofEpochMilli(row.getCreateTime())
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    row.getRemark(),
                    review(row.getReviewState()),
                    row.getCalculatedAsOfDate()
                };
                var target = sheet.createRow(index++);
                for (int i = 0; i < values.length; i++)
                    target.createCell(i)
                            .setCellValue(values[i] == null ? "" : values[i].toString());
            }
            sheet.createFreezePane(1, 1);
            sheet.setAutoFilter(
                    new org.apache.poi.ss.util.CellRangeAddress(
                            0, rows.size(), 0, titles.length - 1));
            book.write(output);
            return output.toByteArray();
        }
    }

    private static String source(String value) {
        if (value == null) return "收费系统";
        return switch (value) {
            case "HISTORICAL_IMPORT" -> "历史导入";
            case "SETTING", "AUTO", "CHARGING" -> "收费系统";
            default -> value;
        };
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }

    private static String yes(Boolean value) {
        return Boolean.TRUE.equals(value) ? "是" : "否";
    }

    private static String payment(String value) {
        return switch (Objects.toString(value, "")) {
            case "PAID" -> "已支付";
            case "PARTIAL" -> "部分支付";
            case "UNPAID" -> "待支付";
            default -> "";
        };
    }

    private static String collection(String value) {
        return switch (Objects.toString(value, "")) {
            case "PAID" -> "已回款";
            case "PARTIAL" -> "部分回款";
            case "OVERDUE" -> "逾期";
            case "NOT_DUE" -> "未到期";
            case "PENDING" -> "待回款";
            default -> "";
        };
    }

    private static String review(String value) {
        return switch (Objects.toString(value, "")) {
            case "APPROVED" -> "审核通过";
            case "REJECTED" -> "已驳回";
            default -> "待审核";
        };
    }
}
