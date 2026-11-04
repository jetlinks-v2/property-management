package org.jetlinks.pro.property.finance.document;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/** 收据金额大写直接使用十进制整数，不能经过double丢失大额尾数。 */
class ReceiptMoneyTest {
    @Test
    void exactChineseMoneyIncludesYuanAndZerosAcrossGroups() {
        var cases =
                java.util.Map.ofEntries(
                        java.util.Map.entry("0.00", "零元整"),
                        java.util.Map.entry("0.01", "零元零壹分"),
                        java.util.Map.entry("0.10", "零元壹角"),
                        java.util.Map.entry("10.01", "壹拾元零壹分"),
                        java.util.Map.entry("100.00", "壹佰元整"),
                        java.util.Map.entry("1001.01", "壹仟零壹元零壹分"),
                        java.util.Map.entry("10001.10", "壹万零壹元壹角"),
                        java.util.Map.entry("100000001.00", "壹亿零壹元整"),
                        java.util.Map.entry("100100001.00", "壹亿零壹拾万零壹元整"),
                        java.util.Map.entry(
                                "9007199254740993.01", "玖仟零柒兆壹仟玖佰玖拾贰亿伍仟肆佰柒拾肆万零玖佰玖拾叁元零壹分"));
        cases.forEach(
                (amount, expected) ->
                        assertEquals(expected, ReceiptMoney.upper(new BigDecimal(amount)), amount));
    }

    @Test
    void rejectsUnsupportedMoneyRatherThanRoundingAwayAnInvalidSnapshot() {
        for (var amount : java.util.List.of("-1", "0.001", "1000000000000000000.00"))
            assertThrows(RuntimeException.class, () -> ReceiptMoney.upper(new BigDecimal(amount)));
        assertThrows(RuntimeException.class, () -> ReceiptMoney.upper(null));
    }
}
