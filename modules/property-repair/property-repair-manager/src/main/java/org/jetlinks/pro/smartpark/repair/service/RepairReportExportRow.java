package org.jetlinks.pro.smartpark.repair.service;

import lombok.Getter;
import lombok.Setter;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** 仅导出查询携带源ID；与聚合指标在同一SQL快照中取得，不进入页面响应。 */
@Getter @Setter
public class RepairReportExportRow extends RepairReportRow {
    @JsonIgnore
    private String orderIdsJson;
    public List<String> sourceOrderIds() { return JSON.parseArray(orderIdsJson, String.class); }
}
