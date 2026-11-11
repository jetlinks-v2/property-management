package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.model.*;
import org.junit.jupiter.api.Test;

class FacilityRulesTest {

  @Test
  void standardsRequireWorkTypeAndValidDistinctChoiceOptions() {
    var standard = new org.jetlinks.pro.property.facility.entity.StandardEntity();
    standard.setName("standard");
    var configuration = new FacilityConfiguration(null, null, null, null, null, null, null);
    assertThrows(BusinessException.class, () -> configuration.standard(standard));
    var check = item();
    check.setKind(null);
    assertThrows(BusinessException.class, () -> FacilityRules.standard(List.of(check)));
    check.setKind("choice");
    for (var options : List.of(List.<String>of(), List.of(" "), List.of("正常", " 正常"))) {
      check.setOptions(options);
      assertThrows(BusinessException.class, () -> FacilityRules.standard(List.of(check)));
    }
    check.setOptions(List.of("正常", "异常"));
    assertDoesNotThrow(() -> FacilityRules.standard(List.of(check)));
    check.setKind("number");
    check.setMinimum(BigDecimal.TEN);
    check.setMaximum(BigDecimal.ONE);
    assertThrows(BusinessException.class, () -> FacilityRules.standard(List.of(check)));
  }

  private CheckItem item() {
    var item = new CheckItem();
    item.setId("pressure");
    item.setName("水泵压力");
    item.setKind("number");
    item.setRequired(true);
    item.setPhotoRequired(true);
    item.setMinimum(BigDecimal.ONE);
    item.setMaximum(BigDecimal.TEN);
    return item;
  }

  private CheckResult result(String value) {
    var r = new CheckResult();
    r.setItemId("pressure");
    r.setValue(value);
    r.setAttachments(List.of("photo"));
    return r;
  }

  @Test
  void outOfRangeCreatesFaultButNormalDoesNot() {
    assertTrue(FacilityRules.results(List.of(item()), List.of(result("11"))));
    assertFalse(FacilityRules.results(List.of(item()), List.of(result("5"))));
  }

  @Test
  void requiredPhotosAndUnknownOrDuplicateChecksRejected() {
    var r = result("5");
    r.setAttachments(List.of());
    assertThrows(BusinessException.class, () ->
      FacilityRules.results(List.of(item()), List.of(r))
    );
    assertThrows(BusinessException.class, () ->
      FacilityRules.results(List.of(item()), List.of())
    );
    assertThrows(BusinessException.class, () ->
      FacilityRules.results(List.of(item()), List.of(result("5"), result("5")))
    );
  }

  @Test
  void monthlyPlanUsesShanghaiCalendar() {
    long january = ZonedDateTime.of(
      2026,
      1,
      31,
      8,
      0,
      0,
      0,
      ZoneId.of("Asia/Shanghai")
    )
      .toInstant()
      .toEpochMilli();
    long february = FacilityRules.next(january, 1, "month");
    assertEquals(
      28,
      Instant.ofEpochMilli(february)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .getDayOfMonth()
    );
  }
}
