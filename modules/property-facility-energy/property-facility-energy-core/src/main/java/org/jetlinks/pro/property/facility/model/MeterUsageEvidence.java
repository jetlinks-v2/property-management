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
  public record Readout(BigDecimal value, BigDecimal rawValue, Long time) implements Serializable {}
}
