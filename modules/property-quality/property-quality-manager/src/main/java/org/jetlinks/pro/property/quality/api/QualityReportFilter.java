package org.jetlinks.pro.property.quality.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 公司按业态、项目按基础档案项目统计；条线时间筛选使用核查完成时间。 */
@Getter @Setter
public class QualityReportFilter {
    @NotNull @Min(1) @Max(2) private Integer taskLevel;
    private String projectId;
    private String businessTypeId;
    private String lineId;
    private Long startTime;
    private Long endTime;
}
