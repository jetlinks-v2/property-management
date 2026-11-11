package org.jetlinks.pro.property.facility.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

/** Server-built evidence for finance; never accepts quantities or customer IDs from the browser. @since 2.20.0 */
public record MeterUsageEvidence(String deviceId, String cloudDeviceId, String deviceName,
  String projectId, String spaceId, String energyType, String month, String subjectId, String standardId,
  String usageVariable, Map<String, BigDecimal> fixedValues, BigDecimal quantity, String sourceFingerprint,
  long allocationSince, String effectiveFrom, String effectiveTo, List<Readout> readings) implements Serializable {
  /** Existing stored financial evidence predates readout snapshots. */
  public MeterUsageEvidence(String deviceId, String cloudDeviceId, String deviceName,
    String projectId, String spaceId, String energyType, String month, String subjectId, String standardId,
    String usageVariable, Map<String, BigDecimal> fixedValues, BigDecimal quantity, String sourceFingerprint,
    long allocationSince, String effectiveFrom, String effectiveTo) {
    this(deviceId,cloudDeviceId,deviceName,projectId,spaceId,energyType,month,subjectId,standardId,usageVariable,
      fixedValues,quantity,sourceFingerprint,allocationSince,effectiveFrom,effectiveTo,List.of());
  }
  /** Explicit normalized field survives command codecs; value remains a compatible JSON alias. */
  @lombok.Getter @lombok.Setter @lombok.NoArgsConstructor @lombok.AllArgsConstructor
  public static class Readout implements Serializable {
    private BigDecimal normalizedValue;
    private BigDecimal rawValue;
    private Long time;
    public BigDecimal value() { return normalizedValue; }
    public BigDecimal getValue() { return normalizedValue; }
    public void setValue(BigDecimal value) { normalizedValue = value; }
    public BigDecimal rawValue() { return rawValue; }
    public Long time() { return time; }
  }
}
