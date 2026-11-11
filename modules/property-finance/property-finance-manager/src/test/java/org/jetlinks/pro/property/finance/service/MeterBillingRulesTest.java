package org.jetlinks.pro.property.finance.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.Map;
import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.jetlinks.pro.property.finance.calculation.*;

class MeterBillingRulesTest {
  @Test void readoutCodecsRetainNormalizedValueAlongsideRawValue() {
    var finance = new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Readout(new BigDecimal("12.50"),new BigDecimal("125"),123L);
    var facility = new org.jetlinks.pro.property.facility.model.MeterUsageEvidence.Readout(new BigDecimal("12.50"),new BigDecimal("125"),123L);
    for (Object reading : java.util.List.of(finance,facility)) {
      var json = com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(reading));
      assertEquals(0,new BigDecimal("12.50").compareTo(json.getBigDecimal("value")));
      assertEquals(0,new BigDecimal("125").compareTo(json.getBigDecimal("rawValue")));
      assertEquals(123L,json.getLong("time"));
      // Command wrappers may consume the conventional value key; normalizedValue must stand alone.
      json.remove("value");
      assertEquals(new BigDecimal("12.50"),json.toJavaObject(org.jetlinks.pro.property.finance.meter.MeterBillingModels.Readout.class).value());
    }
    var stored = com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(finance),org.jetlinks.pro.property.finance.meter.MeterBillingModels.Readout.class);
    var transported = com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(facility),org.jetlinks.pro.property.facility.model.MeterUsageEvidence.Readout.class);
    assertEquals(finance.value(),stored.value()); assertEquals(facility.value(),transported.value());
  }
  private StandardEntity standard() {
    var s = new StandardEntity(); s.setPrice(new BigDecimal("3.25"));
    s.setFormulaExpression("${STANDARD_PRICE} * ${WATER_USAGE}");
    s.setVariablesSnapshotJson("[{\"variableCode\":\"STANDARD_PRICE\",\"variableName\":\"单价\"},{\"variableCode\":\"WATER_USAGE\",\"variableName\":\"用量\"}]");
    s.setChargeRoundType(2); s.setMantissa(0); return s;
  }
  @Test void realFormulaUsesServerUsageAndPriceAndRounding() {
    var r = new MeterBillingRules(new ChargeFormulaParser(), new ChargeFormulaEvaluator());
    assertEquals(new BigDecimal("40.12"), r.amount(standard(), "WATER_USAGE", new BigDecimal("12.346"), Map.of(), null));
    assertEquals(new BigDecimal("0.00"), r.amount(standard(), "WATER_USAGE", BigDecimal.ZERO, Map.of(), null));
    assertThrows(RuntimeException.class, () -> r.amount(standard(), "STANDARD_PRICE", BigDecimal.ONE, Map.of(), null));
  }
  @Test void billingIdentityCannotChangeWithRuleOrApplication() {
    assertEquals(MeterBillingRules.key("t", "p", "meter", "2026-10"), MeterBillingRules.key("t", "p", "meter", "2026-10"));
    assertNotEquals(MeterBillingRules.key("t", "p", "meter", "2026-10"), MeterBillingRules.key("t", "p", "meter", "2026-11"));
  }
  @Test void electricityFormulaFixedFactorsAndRoundingAreEvaluatedByTheSameEngine() {
    var r = new MeterBillingRules(new ChargeFormulaParser(), new ChargeFormulaEvaluator());
    var s = standard(); s.setPrice(new BigDecimal("0.56"));
    s.setFormulaExpression("${STANDARD_PRICE} * ${KWH} * ${FACTOR}");
    s.setVariablesSnapshotJson("[{\"variableCode\":\"STANDARD_PRICE\",\"variableName\":\"单价\"},{\"variableCode\":\"KWH\",\"variableName\":\"电量\"},{\"variableCode\":\"FACTOR\",\"variableName\":\"系数\"}]");
    assertEquals(new BigDecimal("12.38"),r.amount(s,"KWH",new BigDecimal("20.1"),Map.of("FACTOR",new BigDecimal("1.1")),null));
    assertThrows(RuntimeException.class,()->r.amount(s,"KWH",BigDecimal.ONE,Map.of(),null));
    assertThrows(RuntimeException.class,()->r.amount(s,"KWH",BigDecimal.ONE,Map.of("FACTOR",BigDecimal.ONE,"EXTRA",BigDecimal.ONE),null));
    assertThrows(RuntimeException.class,()->r.amount(s,"KWH",BigDecimal.ONE.negate(),Map.of("FACTOR",BigDecimal.ONE),null));
    s.setMantissa(1);
    assertEquals(new BigDecimal("12.38"),r.amount(s,"KWH",new BigDecimal("20.1"),Map.of("FACTOR",new BigDecimal("1.1")),null));
    s.setMantissa(2);
    assertEquals(new BigDecimal("12.39"),r.amount(s,"KWH",new BigDecimal("20.1"),Map.of("FACTOR",new BigDecimal("1.1")),null));
  }
}
