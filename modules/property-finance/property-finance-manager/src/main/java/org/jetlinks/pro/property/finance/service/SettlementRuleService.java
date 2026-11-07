package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.tax.RecordedInfoInput;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 旧系统未实现手续费消费者；此处明确整笔匹配费率与分段累进不同，供配置预览使用。 */
@Service
public class SettlementRuleService {
    public void validate(RecordedInfoInput input) {
        var type = input.getServiceChargeType();
        var tiers = input.getTiers() == null ? List.<RecordedInfoInput.Tier>of() : input.getTiers();
        if (type == null) {
            if (!tiers.isEmpty()
                    || input.getMinimumFee() != null
                    || input.getMaximumFee() != null
                    || input.getFixedAmount() != null) throw error("recorded_fee_invalid", 400);
            return;
        }
        if (type == 2) {
            money(input.getFixedAmount(), true);
            if (!tiers.isEmpty() || input.getMinimumFee() != null || input.getMaximumFee() != null)
                throw error("recorded_fee_invalid", 400);
            return;
        }
        if (type != 1 || input.getFixedAmount() != null || tiers.isEmpty() || tiers.size() > 50)
            throw error("recorded_fee_invalid", 400);
        money(input.getMinimumFee(), false);
        money(input.getMaximumFee(), false);
        if (input.getMinimumFee() != null
                && input.getMaximumFee() != null
                && input.getMinimumFee().compareTo(input.getMaximumFee()) > 0)
            throw error("recorded_fee_invalid", 400);
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            var tier = tiers.get(i);
            if (tier == null) throw error("recorded_tier_invalid", 400);
            money(tier.minimumAmount(), true);
            money(tier.maximumAmount(), false);
            if (tier.minimumAmount().compareTo(previous) != 0
                    || tier.procedureRate() == null
                    || tier.procedureRate().signum() < 0
                    || tier.procedureRate().compareTo(BigDecimal.valueOf(100)) > 0
                    || tier.procedureRate().scale() > 6
                    || (tier.maximumAmount() != null
                            && tier.maximumAmount().compareTo(tier.minimumAmount()) <= 0)
                    || (i < tiers.size() - 1 && tier.maximumAmount() == null)
                    || (i == tiers.size() - 1 && tier.maximumAmount() != null))
                throw error("recorded_tier_invalid", 400);
            previous = tier.maximumAmount();
        }
    }

    /** 预览采用百分数、[下限,上限)、最后一档无上限；先限保底封顶，再按分四舍五入。 */
    public BigDecimal preview(RecordedInfoInput input, BigDecimal amount) {
        validate(input);
        money(amount, true);
        if (input.getServiceChargeType() == null) throw error("recorded_fee_unconfigured", 409);
        if (input.getServiceChargeType() == 2)
            return input.getFixedAmount().setScale(2, RoundingMode.HALF_UP);
        var tier =
                input.getTiers().stream()
                        .filter(
                                row ->
                                        amount.compareTo(row.minimumAmount()) >= 0
                                                && (row.maximumAmount() == null
                                                        || amount.compareTo(row.maximumAmount())
                                                                < 0))
                        .findFirst()
                        .orElseThrow(() -> error("recorded_tier_invalid", 400));
        var fee = amount.multiply(tier.procedureRate()).movePointLeft(2);
        if (input.getMinimumFee() != null) fee = fee.max(input.getMinimumFee());
        if (input.getMaximumFee() != null) fee = fee.min(input.getMaximumFee());
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private void money(BigDecimal value, boolean required) {
        if ((value == null && required)
                || (value != null
                        && (value.signum() < 0
                                || value.scale() > 2
                                || value.precision() - value.scale() > 16)))
            throw error("recorded_fee_invalid", 400);
    }
}
