package org.jetlinks.pro.property.finance.setting;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 只接受可编辑变量；单价、面积和账期月份均由可信来源提供。 */
@Getter
@Setter
public class SettingInput {
    private String spaceId;
    private String projectSubjectId;
    private String standardId;
    private List<String> customerRelationshipIds;
    private String effectiveStartDate;
    private String effectiveEndDate;
    private Map<String, String> variableValues;
    private String remark;
    private Long version;
}
