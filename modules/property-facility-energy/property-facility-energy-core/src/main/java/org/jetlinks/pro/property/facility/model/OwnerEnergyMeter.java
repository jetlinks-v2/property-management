package org.jetlinks.pro.property.facility.model;

import java.io.Serializable;
import java.util.List;

/** 不含设备凭据、原始上报或其他住户；数值保留原精度，展示端统一两位小数。 @since 2.20.0 */
public record OwnerEnergyMeter(String id, String name, String energyType, String usage,
  boolean complete, Long lastTime, List<Day> days) implements Serializable {
  public record Day(String date, String usage, boolean complete) implements Serializable {}
}
