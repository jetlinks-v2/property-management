package org.jetlinks.pro.smartpark.repair.api;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;

/** 更换流程所属分类；携带编辑时的版本，分类资料不接受客户端覆盖。 */
@Getter @Setter
public class RepairFlowMoveRequest {
    @NotNull private Long sourceModifyTime;
    @NotNull private Map<String, Object> configuration;
}
