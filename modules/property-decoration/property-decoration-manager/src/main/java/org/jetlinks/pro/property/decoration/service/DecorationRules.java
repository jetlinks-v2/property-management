package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 状态与快照不变量唯一实现；不依靠前端按钮可见性保护业务流转。 */
public final class DecorationRules {
    private DecorationRules() {}
    public static BusinessException error(String code, int status) {
        return new BusinessException.NoStackTrace("error.decoration." + code, status);
    }
    public static void require(boolean condition, String code) { if (!condition) throw error(code, 409); }
    /** 有分类排序的检查项先按分类再按小项排序；未设置分类排序的现有配置沿用整体排序。 */
    public static void sortChecks(List<DecorationModels.CheckItem> items) {
        java.util.Comparator<DecorationModels.CheckItem> order = java.util.Comparator.comparingInt(DecorationModels.CheckItem::getSort);
        if (items.stream().anyMatch(item -> item.getCategorySort() != null)) {
            order = java.util.Comparator.comparing(DecorationModels.CheckItem::getCategorySort, java.util.Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(DecorationModels.CheckItem::getCategory, java.util.Comparator.nullsFirst(String::compareTo)).thenComparing(order);
        }
        items.sort(order);
    }
    public static void active(DecorationApplicationEntity row) { require(row.getActualEndTime() == null, "ended"); }
    public static void editable(DecorationApplicationEntity row) {
        active(row); require(row.getPaymentStatus() == 0 && row.getPermit() == null, "payment_locked");
    }
    public static void amount(BigDecimal amount) {
        require(amount != null && amount.signum() > 0 && amount.compareTo(new BigDecimal("9999999999.99")) <= 0
            && amount.stripTrailingZeros().scale() <= 2, "invalid_amount");
    }
    public static String reviewedStatus(DecorationApplicationEntity row) {
        if (row.getDepositReviewStatus() == 2 || row.getDetails().getMaterials().stream().anyMatch(m -> m.getReviewStatus() == 2)) return "rejected";
        boolean approved = row.getDepositReviewStatus() == 1
            && row.getDetails().getMaterials().stream().allMatch(m -> !m.getDefinition().isRequired() && m.getFileIds().isEmpty() || m.getReviewStatus() == 1);
        return approved ? (row.getPaymentStatus() == 2 ? "pending_permit" : "pending_payment") : "reviewing";
    }
    public static void paid(DecorationApplicationEntity row) {
        active(row); require(row.getPaymentStatus() == 2, "payment_required");
        require(row.getDepositReviewStatus() == 1 && row.getDetails().getMaterials().stream().allMatch(m -> !m.getDefinition().isRequired() && m.getFileIds().isEmpty() || m.getReviewStatus() == 1), "review_required");
    }
    public static void checks(List<DecorationModels.CheckItem> definitions, List<DecorationModels.CheckResult> results) {
        require(results.size() == results.stream().map(DecorationModels.CheckResult::getItemId).distinct().count(), "invalid_checks");
        for (var result : results) require(definitions.stream().anyMatch(d -> d.isEnabled() && Objects.equals(d.getId(), result.getItemId())), "invalid_checks");
        for (var item : definitions) {
            if (!item.isEnabled()) continue;
            var result = results.stream().filter(r -> item.getId().equals(r.getItemId())).findFirst().orElse(null);
            if (result == null) { require(!item.isRequired(), "required_check"); continue; }
            boolean filled = "image".equals(item.getInputType()) ? !result.getFileIds().isEmpty() : result.getValue() != null && !result.getValue().isBlank();
            require(!item.isRequired() || filled, "required_check");
            if (filled && "option".equals(item.getInputType())) require(item.getOptions().contains(result.getValue()), "invalid_checks");
            if (filled && "number".equals(item.getInputType())) {
                try {
                    BigDecimal value = new BigDecimal(result.getValue());
                    boolean inRange = (item.getMin() == null || value.compareTo(item.getMin()) >= 0)
                        && (item.getMax() == null || value.compareTo(item.getMax()) <= 0);
                    require(!Boolean.TRUE.equals(result.getQualified()) || inRange, "invalid_checks");
                } catch (NumberFormatException e) { throw error("invalid_checks", 400); }
            }
            require(result.getQualified() != null, "invalid_checks");
            if (!result.getQualified()) require(result.getRectification() != null && !result.getRectification().isBlank() && result.getDeadline() != null, "rectification_required");
        }
    }
}
