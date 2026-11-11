package org.jetlinks.pro.property.finance.meter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/** Public meter billing results; quantity and formula values are always assembled by providers. @since 2.20.0 */
public final class MeterBillingModels {
  private MeterBillingModels() {}
  public record Variable(String code, String name, boolean system) implements Serializable {}
  public record Standard(String id, long version, String subjectId, String subjectName, String name,
    String formula, BigDecimal price, String effectiveFrom, String effectiveTo, List<Variable> variables) implements Serializable {}
  public record Result(String deviceId, String deviceName, String projectId, String spaceId, String fullName,
    String customerName, String energyType, String month, BigDecimal quantity, BigDecimal amount,
    String subjectName, String standardName, String fingerprint, String billId, String billNo) implements Serializable {}
}
