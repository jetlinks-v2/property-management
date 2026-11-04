package org.jetlinks.pro.property.quality.api;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;

/** 问题明细分别筛选工单及检查项；分页归检查项，避免工单分页截断问题集合。 */
@Getter @Setter
public class QualityProblemQuery {
    @Valid @NotNull private QueryParamEntity orderQuery = new QueryParamEntity();
    @Valid @NotNull private QueryParamEntity recordQuery = new QueryParamEntity();
}
