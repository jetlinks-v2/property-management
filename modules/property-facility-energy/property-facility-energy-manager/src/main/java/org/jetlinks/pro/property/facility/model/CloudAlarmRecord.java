package org.jetlinks.pro.property.facility.model;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 平台公开告警查询的只读投影，不引入规则引擎内部实体或服务依赖。 */
@Getter
@Setter
public class CloudAlarmRecord {
  private String id, alarmConfigId, alarmName, sourceType, sourceId, targetType, targetId;
  // Platform Topic bodies are history entries. Their own id is not the current record id.
  private String alarmRecordId;
  private String description, triggerDesc, actualDesc;
  private Long alarmTime, lastAlarmTime, handleTime;
  private Integer level;
  private Object state;

  public String stateValue() {
    if (state instanceof Map<?, ?> value) return java.util.Objects.toString(value.get("value"), "");
    if (state instanceof org.hswebframework.web.dict.EnumDict<?> value) return String.valueOf(value.getValue());
    return java.util.Objects.toString(state, "");
  }

  /** 平台等级可自定义，不把数字强行解释为本地“严重”；原值单独保留供展示。 */
  public String facilityLevel() { return "warning"; }

  public boolean belongsTo(String deviceId) {
    return "device".equals(sourceType) && deviceId.equals(sourceId)
      || "device".equals(targetType) && deviceId.equals(targetId);
  }
}
