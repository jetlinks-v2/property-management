package org.jetlinks.pro.property.quality.api;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 首页日期按上海自然日解释，项目筛选始终与当前应用资产权限取交集。 */
@Getter
@Setter
public class QualityHomeQuery {
    private String projectId;
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
    private String startDate;
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
    private String endDate;
    @Pattern(regexp = "day|week|month")
    private String trendType = "day";
}
