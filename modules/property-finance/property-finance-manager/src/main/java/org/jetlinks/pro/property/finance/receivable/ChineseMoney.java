package org.jetlinks.pro.property.finance.receivable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** 打印金额使用整数分逐组转换，不能经过double丢失大额尾数。 */
public final class ChineseMoney {
    private static final String[] DIGITS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] GROUPS = {"", "万", "亿", "兆", "京", "垓"};

    private ChineseMoney() {}

    public static String format(BigDecimal amount) {
        if (amount == null || amount.signum() < 0)
            throw new IllegalArgumentException("Nonnegative amount required");
        var cents =
                amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).toBigIntegerExact();
        var parts = cents.divideAndRemainder(BigInteger.valueOf(100));
        var number = parts[0];
        if (number.toString().length() > 24)
            throw new IllegalArgumentException("Amount exceeds supported groups");
        var groups = new java.util.ArrayList<Integer>();
        do {
            var next = number.divideAndRemainder(BigInteger.valueOf(10000));
            groups.add(next[1].intValue());
            number = next[0];
        } while (number.signum() > 0);
        var result = new StringBuilder();
        boolean gap = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            int value = groups.get(i);
            if (value == 0) {
                if (!result.isEmpty()) gap = true;
                continue;
            }
            if (!result.isEmpty() && (gap || value < 1000)) result.append("零");
            result.append(group(value)).append(GROUPS[i]);
            gap = false;
        }
        if (result.isEmpty()) result.append("零");
        result.append("元");
        int jiao = parts[1].intValue() / 10, fen = parts[1].intValue() % 10;
        if (jiao > 0) result.append(DIGITS[jiao]).append("角");
        if (jiao == 0 && fen > 0) result.append("零");
        if (fen > 0) result.append(DIGITS[fen]).append("分");
        if (jiao == 0 && fen == 0) result.append("整");
        return result.toString();
    }

    private static String group(int value) {
        String[] units = {"仟", "佰", "拾", ""};
        var result = new StringBuilder();
        boolean zero = false;
        int divisor = 1000;
        for (int i = 0; i < 4; i++, divisor /= 10) {
            int digit = value / divisor % 10;
            if (digit == 0) {
                if (!result.isEmpty()) zero = true;
                continue;
            }
            if (zero) result.append("零");
            result.append(DIGITS[digit]).append(units[i]);
            zero = false;
        }
        return result.toString();
    }
}
