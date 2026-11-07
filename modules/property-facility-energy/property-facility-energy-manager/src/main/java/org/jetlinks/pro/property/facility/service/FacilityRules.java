package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.model.*;

/** 纯业务规则，供运行链路及测试使用；无I/O、权限替身或隐藏状态。 */
public final class FacilityRules {

  private FacilityRules() {}

  public static void standard(List<CheckItem> items) {
    require(
      items != null && !items.isEmpty() && items.size() <= 100,
      "invalid_standard"
    );
    var ids = new HashSet<String>();
    for (var i : items) {
      require(
        i.getId() != null &&
          ids.add(i.getId()) &&
          i.getName() != null &&
          !i.getName().isBlank(),
        "invalid_standard"
      );
      require(
        Set.of("number", "text", "boolean", "choice").contains(i.getKind()),
        "invalid_standard"
      );
      if (
        "number".equals(i.getKind()) &&
        i.getMinimum() != null &&
        i.getMaximum() != null
      ) require(
        i.getMinimum().compareTo(i.getMaximum()) <= 0,
        "invalid_standard"
      );
      if ("choice".equals(i.getKind())) require(
        i.getOptions() != null && !i.getOptions().isEmpty(),
        "invalid_standard"
      );
    }
  }

  public static boolean results(
    List<CheckItem> items,
    List<CheckResult> results
  ) {
    require(results != null && results.size() <= 100, "invalid_results");
    var byId = new HashMap<String, CheckResult>();
    for (var result : results)
      require(
        result.getItemId() != null &&
          byId.put(result.getItemId(), result) == null,
        "invalid_results"
      );
    var known = items
      .stream()
      .map(CheckItem::getId)
      .collect(java.util.stream.Collectors.toSet());
    require(known.containsAll(byId.keySet()), "invalid_results");
    boolean abnormal = false;
    for (var item : items) {
      var result = byId.get(item.getId());
      boolean filled =
        result != null &&
        result.getValue() != null &&
        !result.getValue().isBlank();
      require(
        !Boolean.TRUE.equals(item.getRequired()) || filled,
        "required_item"
      );
      if (!filled) continue;
      boolean bad = Boolean.TRUE.equals(result.getAbnormal());
      switch (item.getKind()) {
        case "number" -> {
          BigDecimal n;
          try {
            n = new BigDecimal(result.getValue());
          } catch (NumberFormatException e) {
            throw new org.hswebframework.web.exception.BusinessException(
              "error.facility.invalid_number",
              400
            );
          }
          bad |=
            (item.getMinimum() != null && n.compareTo(item.getMinimum()) < 0) ||
            (item.getMaximum() != null && n.compareTo(item.getMaximum()) > 0);
        }
        case "boolean" -> require(
          Set.of("true", "false").contains(result.getValue()),
          "invalid_results"
        );
        case "choice" -> require(
          item.getOptions().contains(result.getValue()),
          "invalid_results"
        );
        default -> require(
          result.getValue().length() <= 4000,
          "invalid_results"
        );
      }
      require(
        !Boolean.TRUE.equals(item.getPhotoRequired()) ||
          (result.getAttachments() != null &&
            !result.getAttachments().isEmpty()),
        "photo_required"
      );
      result.setAbnormal(bad);
      abnormal |= bad;
    }
    return abnormal;
  }

  public static long next(long current, int value, String unit) {
    require(value > 0 && value <= 365, "invalid_period");
    var date = Instant.ofEpochMilli(current).atZone(ZoneId.of("Asia/Shanghai"));
    return (switch (unit) {
      case "hour" -> date.plusHours(value);
      case "day" -> date.plusDays(value);
      case "week" -> date.plusWeeks(value);
      case "month" -> date.plusMonths(value);
      case "year" -> date.plusYears(value);
      default -> throw new org.hswebframework.web.exception.BusinessException(
        "error.facility.invalid_period",
        400
      );
    })
      .toInstant()
      .toEpochMilli();
  }
}
