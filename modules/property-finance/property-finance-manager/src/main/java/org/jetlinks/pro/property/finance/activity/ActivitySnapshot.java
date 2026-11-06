package org.jetlinks.pro.property.finance.activity;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;

import java.util.List;

/** 服务端解析的优惠范围和标准名称，保留编辑当时的配置依据。 */
@Getter
@Setter
public class ActivitySnapshot {
    private ActivityInput input;
    private String activityTypeName;
    private String subjectName;
    private String standardName;
    private List<PropertySpaceInfo> targets;
}
