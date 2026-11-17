package org.jetlinks.pro.property.facility.model;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 平台告警等级的只读展示投影，不暴露规则配置或提供配置写入能力。 */
@Getter
@Setter
public class CloudAlarmLevels {
  private List<Level> levels;

  @Getter
  @Setter
  public static class Level {
    private Integer level;
    private String title;
    private Map<String, String> i18nMessages;
  }
}
