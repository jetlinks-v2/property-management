package org.jetlinks.pro.smartpark.repair.api;

import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Mono;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 创建或返回同一来源核查记录关联的报修工单。
 * 调用方保留真实认证、租户和来源应用上下文；目标应用重新检查成员、动作和项目权限。
 * 参数存入命令属性以跨 RPC 传输，失败向调用方传播；创建命令按来源记录幂等。
 */
public class CreateQualityRepairCommand extends AbstractCommand<Mono<QualityRepairInfo>, CreateQualityRepairCommand> {
    public static final String SERVICE_ID = "propertyRepair:quality";

    @NotBlank
    public String getTargetApplicationId() {
        return getOrNull("targetApplicationId", String.class);
    }

    public void setTargetApplicationId(String targetApplicationId) {
        with("targetApplicationId", targetApplicationId);
    }

    @NotBlank
    public String getSourceRecordId() {
        return getOrNull("sourceRecordId", String.class);
    }

    public void setSourceRecordId(String sourceRecordId) {
        with("sourceRecordId", sourceRecordId);
    }

    @Valid @NotNull
    public CreateRepairRequest getRequest() {
        return getOrNull("request", CreateRepairRequest.class);
    }

    public void setRequest(CreateRepairRequest request) {
        with("request", request);
    }
}
