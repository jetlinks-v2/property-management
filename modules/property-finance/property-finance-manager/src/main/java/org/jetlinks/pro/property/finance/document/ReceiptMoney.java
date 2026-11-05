package org.jetlinks.pro.property.finance.document;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 收据人民币大写：全程十进制，支持财务金额上限18位整数及两位小数。 */
public final class ReceiptMoney {
    private static final String DIGITS = "零壹贰叁肆伍陆柒捌玖";
    private static final String[] GROUPS = {"", "万", "亿", "兆", "京"};
    private static final String[] PLACES = {"", "拾", "佰", "仟"};

    private ReceiptMoney() {}

    public static String upper(BigDecimal amount) {
        if (amount == null || amount.signum() < 0)
            throw new IllegalArgumentException("Invalid receipt amount");
        var exact = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (exact.precision() > 20)
            throw new IllegalArgumentException("Receipt amount out of range");
        String integral = exact.toBigInteger().toString();
        var result = new StringBuilder();
        int count = (integral.length() + 3) / 4;
        boolean pendingZero = false;
        for (int index = count - 1; index >= 0; index--) {
            int end = integral.length() - index * 4;
            int group = Integer.parseInt(integral.substring(Math.max(0, end - 4), end));
            if (group == 0) {
                pendingZero = result.length() > 0;
                continue;
            }
            if (result.length() > 0 && (pendingZero || group < 1000)) result.append('零');
            result.append(group(group)).append(GROUPS[index]);
            pendingZero = false;
        }
        if (result.length() == 0) result.append('零');
        result.append('元');
        int fraction = exact.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        if (fraction == 0) return result.append('整').toString();
        int jiao = fraction / 10, fen = fraction % 10;
        if (jiao > 0) result.append(DIGITS.charAt(jiao)).append('角');
        else result.append('零');
        if (fen > 0) result.append(DIGITS.charAt(fen)).append('分');
        return result.toString();
    }

    private static String group(int number) {
        var result = new StringBuilder();
        boolean zero = false;
        for (int place = 3, divisor = 1000; place >= 0; place--, divisor /= 10) {
            int digit = number / divisor % 10;
            if (digit == 0) {
                zero = result.length() > 0;
            } else {
                if (zero) result.append('零');
                result.append(DIGITS.charAt(digit)).append(PLACES[place]);
                zero = false;
            }
        }
        return result.toString();
    }
}
