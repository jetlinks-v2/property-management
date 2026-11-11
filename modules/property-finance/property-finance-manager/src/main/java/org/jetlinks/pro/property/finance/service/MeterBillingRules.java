package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.jetlinks.pro.property.finance.standard.StandardSnapshots;
import org.springframework.stereotype.Component;

/** Meter quantities are not time-prorated: the measured complete interval already defines coverage. */
@Component @RequiredArgsConstructor
public class MeterBillingRules {
  private static final Set<String> TRUSTED = Set.of("STANDARD_PRICE", "BILLING_AREA", "PERIOD_MONTHS");
  public static final Set<String> SYSTEM = Set.of("STANDARD_PRICE", "BILLING_AREA", "PERIOD_MONTHS", "WATER_USAGE", "ELECTRICITY_USAGE", "GAS_USAGE");
  private final ChargeFormulaParser parser;
  private final ChargeFormulaEvaluator evaluator;
  public BigDecimal amount(StandardEntity standard, String usageVariable, BigDecimal usage,
      Map<String, BigDecimal> fixed, BigDecimal area) {
    return calculate(standard, values(standard, usageVariable, usage, fixed, area));
  }
  public Map<String, BigDecimal> values(StandardEntity standard, String usageVariable, BigDecimal usage,
      Map<String, BigDecimal> fixed, BigDecimal area) {
    if (TRUSTED.contains(Objects.toString(usageVariable, "")) || usage == null || usage.signum() < 0) throw error("meter_rule_invalid", 400);
    var values = new TreeMap<String, BigDecimal>();
    var definitions = StandardSnapshots.variables(standard.getVariablesSnapshotJson());
    if (definitions.stream().noneMatch(v -> v.variableCode().equals(usageVariable))) throw error("meter_rule_invalid", 409);
    var expected = new HashSet<String>();
    for (var variable : definitions) {
      String code = variable.variableCode();
      if (org.jetlinks.pro.property.finance.meter.MeterBillingModels.isUsageCode(code) && !code.equals(usageVariable))
        throw error("meter_rule_invalid", 400);
      BigDecimal value = switch(code) {
        case "STANDARD_PRICE" -> standard.getPrice();
        case "BILLING_AREA" -> area;
        case "PERIOD_MONTHS" -> BigDecimal.ONE;
        default -> { if (!code.equals(usageVariable)) expected.add(code); yield code.equals(usageVariable) ? usage : fixed.get(code); }
      };
      if (value == null || value.signum() < 0 || value.precision() > 30) throw error("meter_variable_missing", 409);
      values.put(code, value);
    }
    if (!expected.equals(fixed.keySet())) throw error("meter_rule_invalid", 400);
    return values;
  }
  public BigDecimal calculate(StandardEntity standard, Map<String, BigDecimal> values) {
    return SettingRules.money(evaluator.evaluate(parser.parse(standard.getFormulaExpression(), SettingRules.names(standard)),
      values, standard.getChargeRoundType(), SettingRules.rounding(standard)));
  }
  public static String key(String tenant, String project, String cloudId, String month) {
    return "METER:" + DigestUtils.sha256Hex(com.alibaba.fastjson.JSON.toJSONString(List.of(tenant, project, cloudId, month)));
  }
}
