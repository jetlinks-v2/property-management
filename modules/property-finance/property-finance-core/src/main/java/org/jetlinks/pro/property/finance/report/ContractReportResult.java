package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.exception.BusinessException;

import java.math.BigDecimal;
import java.util.Set;

/** 金额均以精确十进制文本输出；不暴露镜像原载荷或账单内部计算JSON。 @since 2.20.0 */
public record ContractReportResult(
        String projectId,
        String projectName,
        String month,
        Amounts summary,
        PagerResult<Row> page) {
    public record Row(
            String id,
            String code,
            String name,
            String customerName,
            String spaceId,
            String fullName,
            String startDate,
            String endDate,
            String leaseArea,
            Amounts amounts) {}

    public record Detail(
            String receivableId,
            String fullName,
            String subjectName,
            String customerName,
            String calcStartDate,
            String calcEndDate,
            String contractAmount,
            String openingPaid,
            String closingPaid,
            String amount) {}

    public record Amounts(
            String openingReceivable,
            String openingAdvance,
            String currentReceivable,
            String currentNetReceived,
            String closingReceivable,
            String closingAdvance) {
        public static final Set<String> METRICS =
                Set.of(
                        "openingReceivable",
                        "openingAdvance",
                        "currentReceivable",
                        "currentNetReceived",
                        "closingReceivable",
                        "closingAdvance");

        public static Amounts zero() {
            return new Amounts("0.00", "0.00", "0.00", "0.00", "0.00", "0.00");
        }

        public Amounts plus(Amounts b) {
            return new Amounts(
                    add(openingReceivable, b.openingReceivable),
                    add(openingAdvance, b.openingAdvance),
                    add(currentReceivable, b.currentReceivable),
                    add(currentNetReceived, b.currentNetReceived),
                    add(closingReceivable, b.closingReceivable),
                    add(closingAdvance, b.closingAdvance));
        }

        private static String add(String a, String b) {
            return new BigDecimal(a).add(new BigDecimal(b)).toPlainString();
        }

        public String value(String metric) {
            if (metric == null) throw error("report_query_invalid", 400);
            return switch (metric) {
                case "openingReceivable" -> openingReceivable;
                case "openingAdvance" -> openingAdvance;
                case "currentReceivable" -> currentReceivable;
                case "currentNetReceived" -> currentNetReceived;
                case "closingReceivable" -> closingReceivable;
                case "closingAdvance" -> closingAdvance;
                default -> throw error("report_query_invalid", 400);
            };
        }

        private static BusinessException error(String key, int status) {
            return new BusinessException.NoStackTrace("error.property_finance_" + key, status);
        }
    }
}
