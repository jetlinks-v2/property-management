package org.jetlinks.pro.smartpark.repair.service;

/** 固定导出投影，不能由客户端指定SQL、列名或任意文件模板。 */
public enum RepairExportType {
    orders("报事记录"), review("报事审核"), summary("报事统计"), node("报事节点统计"),
    paid("有偿报事统计"), overdue("超月报事统计"), follow("跟进情况统计"), evaluation("评价管理");

    private final String title;
    RepairExportType(String title) { this.title = title; }
    public String title() { return title; }
    public boolean aggregated() { return this != orders && this != review && this != evaluation; }
}
