package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.model.*;
import org.junit.jupiter.api.Test;

class FacilityRulesTest {

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
