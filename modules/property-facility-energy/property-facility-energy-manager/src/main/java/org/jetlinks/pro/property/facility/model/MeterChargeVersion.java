package org.jetlinks.pro.property.facility.model;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.jetlinks.pro.property.facility.entity.MeterChargeEntity;

/** Immutable rule facts retained when a future rule takes effect; completed bills keep their own snapshot. */
public record MeterChargeVersion(Long version, String subjectId, String standardId, String usageVariable,
    Map<String,BigDecimal> fixedValues, String effectiveFrom, String effectiveTo) implements Serializable {
  public static MeterChargeVersion of(MeterChargeEntity row, String through) {
    return new MeterChargeVersion(row.getVersion(),row.getSubjectId(),row.getStandardId(),row.getUsageVariable(),
      Map.copyOf(row.getFixedValues()),row.getEffectiveFrom(),through);
  }
  public boolean covers(LocalDate first, LocalDate last) {
    return effectiveFrom.compareTo(first.toString()) <= 0 && (effectiveTo == null || effectiveTo.compareTo(last.toString()) >= 0);
  }
}
