package org.jetlinks.pro.smartpark.repair.service;
import java.util.List;
/** 首页可见图表，与旧版走势、紧急程度、分类、项目占比对应。 */
public record RepairDashboard(List<RepairDashboardPoint> trend,List<RepairDashboardPoint> urgency,List<RepairDashboardPoint> category) {}
