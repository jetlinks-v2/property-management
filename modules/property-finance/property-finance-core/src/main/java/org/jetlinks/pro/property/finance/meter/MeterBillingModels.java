package org.jetlinks.pro.property.finance.meter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Public meter billing results; quantity and formula values are always assembled by providers. @since 2.20.0 */
public final class MeterBillingModels {
  private MeterBillingModels() {}
  /** 公开计量来源约定，与变量是否系统预置分离；未知类型不推断用量。 @since 2.20.0 */
  public static String usageCode(String energyType) {
    return switch (energyType == null ? "" : energyType) {
      case "water" -> "WATER_USAGE";
      case "electricity" -> "ELECTRICITY_USAGE";
      default -> null;
    };
  }
  public static boolean isUsageCode(String code) {
    return "WATER_USAGE".equals(code) || "ELECTRICITY_USAGE".equals(code);
  }
  public record Variable(String code, String name, boolean system) implements Serializable {}
  public record Standard(String id, long version, String subjectId, String subjectName, String name,
    String formula, BigDecimal price, String effectiveFrom, String effectiveTo, List<Variable> variables) implements Serializable {}
  public record Result(String deviceId, String deviceName, String projectId, String spaceId, String fullName,
    String customerName, String energyType, String month, BigDecimal quantity, BigDecimal amount,
    String subjectName, String standardName, String fingerprint, String billId, String billNo, Detail detail) implements Serializable {
    /** Previously persisted previews may not contain calculation details. */
    public Result(String deviceId, String deviceName, String projectId, String spaceId, String fullName,
      String customerName, String energyType, String month, BigDecimal quantity, BigDecimal amount,
      String subjectName, String standardName, String fingerprint, String billId, String billNo) {
      this(deviceId,deviceName,projectId,spaceId,fullName,customerName,energyType,month,quantity,amount,
        subjectName,standardName,fingerprint,billId,billNo,null);
    }
  }
  /** Frozen billing explanation, never a substitute for server-side recalculation. @since 2.20.0 */
  public record Detail(String receivableDate, String dueDate, String formula, BigDecimal price,
    Map<String,BigDecimal> values, List<Readout> readings, String reviewedName, Long reviewedTime, List<Tier> tiers) implements Serializable {
    public Detail(String receivableDate, String dueDate, String formula, BigDecimal price,
      Map<String,BigDecimal> values, List<Readout> readings, String reviewedName, Long reviewedTime) {
      this(receivableDate,dueDate,formula,price,values,readings,reviewedName,reviewedTime,List.of());
    }
  }
  public record Readout(BigDecimal value, BigDecimal rawValue, Long time) implements Serializable {}
  public record Tier(BigDecimal from, BigDecimal to, BigDecimal price, BigDecimal quantity, BigDecimal amount) implements Serializable {}
  public record Bill(String id, String accountPeriod, String fullName, String customerName, String subjectName,
    String billNo, String receivableDate, String dueDate, Result result) implements Serializable {}
}
