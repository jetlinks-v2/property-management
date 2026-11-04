package org.jetlinks.pro.smartpark.repair.service;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;

/**
 * 工单文件专用投影，分类层级由导出查询关联当前应用、业务项目的字典。
 * 不持久化分类冗余字段；普通列表和工单写入仍使用原实体。
 */
@Getter @Setter
public class RepairOrderExportRow extends RepairOrderEntity {
    private String firstCategoryName;
    private String secondCategoryName;
}
