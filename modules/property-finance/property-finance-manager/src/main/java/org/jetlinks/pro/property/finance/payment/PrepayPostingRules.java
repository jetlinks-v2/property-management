package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.activity.PrepayQuoteSnapshot;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.jetlinks.pro.property.finance.setting.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 全期预缴与普通欠费收款分开计提；赠送按冻结逐期优惠，零实收期不伪造支付来源行。 */
@Component
@RequiredArgsConstructor
public class PrepayPostingRules {
    private final ReceivableCalculationService calculation;

    public PaymentRules.Result prepare(
            PrepayQuoteSnapshot snapshot,
            List<ReceivableEntity> originals,
            CashierInput input,
            LocalDate date) {
        PaymentRules.validateFunding(input, date);
        if (originals.size() != snapshot.periods().size())
            throw error("prepay_source_changed", 409);
        var rows = new ArrayList<ReceivableEntity>();
        var items = new ArrayList<CashierInput.Item>();
        for (int index = 0; index < originals.size(); index++) {
            var period = snapshot.periods().get(index);
            var original = originals.get(index);
            if (!period.billKey().equals(original.getBillKey())
                    || original.getPaidAmount().signum() != 0
                    || Boolean.TRUE.equals(original.getDeleted())
                    || Boolean.TRUE.equals(original.getSplit())
                    || Boolean.TRUE.equals(original.getFinancialClosed())
                    || Boolean.TRUE.equals(original.getFrozen())
                    || Boolean.TRUE.equals(original.getPaymentLocked())
                    || Boolean.TRUE.equals(original.getBankCollectionLocked())
                    || Boolean.TRUE.equals(original.getChargeLocked())
                    || Boolean.TRUE.equals(original.getDiscountLocked())
                    || original.getPaymentOccupiedAmount().signum() > 0
                    || original.getInvoiceOccupiedAmount().signum() > 0)
                throw error("prepay_source_changed", 409);
            if (original.getCalculatedAsOfDate() != null
                    && date.isBefore(LocalDate.parse(original.getCalculatedAsOfDate())))
                throw error("receivable_date_regression", 409);
            var row = new ReceivableEntity();
            BeanUtils.copyProperties(original, row);
            var standard = SettingRules.standard(row.getSnapshotJson());
            // CLOSED表示该期已按全期结算，并非财务月结；后续日计提仍保留全期金额，正常退款不受影响。
            if (!"ARREARS".equals(period.type())) row.setPeriodStatus("CLOSED");
            calculation.calculate(row, standard, date, BillingTaskKind.ALL);
            var quoted = new BigDecimal(period.originalAmount());
            var paid = new BigDecimal(period.paidAmount());
            var discount = new BigDecimal(period.discountAmount());
            if (row.getArrears().compareTo(quoted) != 0
                    || paid.signum() < 0
                    || discount.signum() < 0
                    || paid.add(discount).compareTo(quoted) != 0)
                throw error("prepay_source_changed", 409);
            row.setFeeReductionAmount(row.getFeeReductionAmount().add(discount));
            calculation.calculate(row, standard, date, BillingTaskKind.ALL);
            if (row.getArrears().compareTo(paid) != 0) throw error("prepay_source_changed", 409);
            row.setPaidAmount(paid);
            row.setArrears(BigDecimal.ZERO.setScale(2));
            row.setPaidDate(LocalDateTime.parse(input.getPaidAt()).toLocalDate().toString());
            rows.add(row);
            if (paid.signum() > 0)
                items.add(new CashierInput.Item(row.getId(), original.getVersion(), paid));
        }
        input.setItems(List.copyOf(items));
        var total =
                items.stream()
                        .map(CashierInput.Item::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        var tenders =
                input.getTenders().stream()
                        .map(CashierInput.Tender::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal(snapshot.payAmount())) != 0
                || total.compareTo(tenders) != 0) throw error("cashier_total_mismatch", 400);
        return new PaymentRules.Result(
                List.copyOf(rows),
                PaymentRules.allocate(input),
                total.setScale(2).toPlainString(),
                date.toString(),
                "");
    }
}
