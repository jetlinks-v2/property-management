package org.jetlinks.pro.property.finance.tax;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.entity.TaxItemEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 与旧税目导入口径一致的纯校验，数据库精度校验在写入前完成，禁止静默舍入。 */
public final class TaxItemRules {
    private TaxItemRules() {}

    public static final Map<String, Integer> FIELDS =
            Map.ofEntries(
                    Map.entry("goodsName", 255),
                    Map.entry("goodsShortName", 100),
                    Map.entry("goodsCode", 64),
                    Map.entry("classificationName", 255),
                    Map.entry("specAndTypes", 255),
                    Map.entry("unit", 50),
                    Map.entry("favouredPolicyName", 255));
    private static final Set<String> REQUIRED =
            Set.of("goodsName", "goodsCode", "classificationName");

    public static TaxItemEntity validate(TaxItemInput input) {
        if (input == null
                || input.getCompanyId() == null
                || input.getCompanyId().isBlank()
                || input.getCompanyId().length() > 64) throw error("tax_invalid_companyId", 400);
        var item = new TaxItemEntity();
        BeanUtils.copyProperties(input, item);
        var wrapper = new BeanWrapperImpl(item);
        for (var field : FIELDS.entrySet()) {
            var value = (String) wrapper.getPropertyValue(field.getKey());
            value = value == null ? null : value.trim();
            if (value != null && value.isEmpty()) value = null;
            if ((value == null && REQUIRED.contains(field.getKey()))
                    || (value != null && value.length() > field.getValue()))
                throw error("tax_invalid_" + field.getKey(), 400);
            wrapper.setPropertyValue(field.getKey(), value);
        }
        item.setGoodsCode(item.getGoodsCode().toUpperCase(Locale.ROOT));
        var rate = item.getTaxRate();
        if (rate == null
                || rate.signum() < 0
                || rate.compareTo(BigDecimal.ONE) > 0
                || rate.stripTrailingZeros().scale() > 6) throw error("tax_invalid_taxRate", 400);
        var price = item.getPrice();
        if (price != null
                && (price.signum() < 0
                        || price.precision() - price.scale() > 13
                        || price.stripTrailingZeros().scale() > 10))
            throw error("tax_invalid_price", 400);
        item.setIncludingTaxFlag(
                input.getIncludingTaxFlag() == null ? 0 : input.getIncludingTaxFlag());
        item.setFavouredPolicyFlag(
                input.getFavouredPolicyFlag() == null ? 0 : input.getFavouredPolicyFlag());
        if (item.getIncludingTaxFlag() < 0 || item.getIncludingTaxFlag() > 1)
            throw error("tax_invalid_includingTaxFlag", 400);
        if (item.getFavouredPolicyFlag() < 0 || item.getFavouredPolicyFlag() > 1)
            throw error("tax_invalid_favouredPolicyFlag", 400);
        if (item.getFavouredPolicyFlag() == 1 && item.getFavouredPolicyName() == null)
            throw error("tax_invalid_favouredPolicyName", 400);
        item.setMatchKey(matchKey(item.getGoodsName(), item.getGoodsCode()));
        return item;
    }

    public static String matchKey(String name, String code) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (name.trim().toLowerCase(Locale.ROOT)
                                                            + "\n"
                                                            + code.trim().toUpperCase(Locale.ROOT))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
