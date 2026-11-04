package org.jetlinks.pro.smartpark.repair.service;
import lombok.Getter;
import lombok.Setter;
/** 数据库聚合点，名称为日期/分类/紧急程度，数量已应用完整权限范围。 */
@Getter @Setter
public class RepairDashboardPoint {
 private String name;
 private long total, noDistributed, handling, abandoned;
}
