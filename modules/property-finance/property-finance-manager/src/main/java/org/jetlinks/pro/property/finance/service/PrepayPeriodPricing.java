package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.activity.PrepayQuoteRules.Window;
import org.jetlinks.pro.property.finance.calculation.NaturalBillingPeriodService;
import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** 预缴只读定价，沿用正式出账的自然账期、冻结变量、公式和舍入，不读取当前标准覆盖历史。 */
@Component
@RequiredArgsConstructor
public class PrepayPeriodPricing {
    private final NaturalBillingPeriodService periods;
    private final ReceivableCalculationService calculation;

    public Priced price(HouseSettingEntity setting, Window window) {
        if (setting == null
                || window == null
                || window.start() == null
                || window.end() == null
                || window.start().isAfter(window.end())
                || setting.getSnapshotJson() == null) throw error("prepay_invalid", 400);
        var standard = SettingRules.standard(setting.getSnapshotJson());
        if (standard == null
                || !Set.of("MONTHLY", "QUARTERLY")
                        .contains(
                                standard.getSettlementMethod() == null
                                        ? ""
                                        : standard.getSettlementMethod()))
            throw error("prepay_invalid", 400);
        var natural = periods.split(standard.getSettlementMethod(), window.start(), window.end());
        if (natural.size() != 1) throw error("prepay_invalid", 400);
        var row = ReceivableDrafts.create(setting, natural.get(0), standard);
        // 不接受任意缩短覆盖日期来降低报价，只允许自然期间与设置有效期的实际交集。
        if (!window.start().toString().equals(row.getCalcStartDate())
                || !window.end().toString().equals(row.getCalcEndDate()))
            throw error("prepay_invalid", 400);
        var amount =
                calculation.principal(row, standard, window.end()).getContractReceivableAmount();
        return new Priced(window, SettingRules.money(amount), row);
    }

    public record Priced(Window period, BigDecimal amount, ReceivableEntity draft) {}
}
