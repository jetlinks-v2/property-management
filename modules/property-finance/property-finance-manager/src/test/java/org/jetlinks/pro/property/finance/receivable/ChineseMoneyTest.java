package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class ChineseMoneyTest {
    @Test
    void decimalAndGroupZerosNeverPassThroughFloatingPoint() {
        assertEquals("零元整", ChineseMoney.format(new BigDecimal("0.00")));
        assertEquals("壹佰元零壹分", ChineseMoney.format(new BigDecimal("100.01")));
        assertEquals("壹万零壹元整", ChineseMoney.format(new BigDecimal("10001.00")));
        assertEquals("壹亿零壹元整", ChineseMoney.format(new BigDecimal("100000001.00")));
        assertEquals(
                "玖佰兆柒仟壹佰玖拾玖亿贰仟伍佰肆拾柒万肆仟零玖拾玖元玖角壹分",
                ChineseMoney.format(new BigDecimal("900719925474099.91")));
        assertThrows(
                IllegalArgumentException.class, () -> ChineseMoney.format(new BigDecimal("-1")));
        assertThrows(ArithmeticException.class, () -> ChineseMoney.format(new BigDecimal("1.001")));
    }
}
