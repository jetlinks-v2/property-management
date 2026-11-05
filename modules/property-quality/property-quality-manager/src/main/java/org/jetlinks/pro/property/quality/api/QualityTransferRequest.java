package org.jetlinks.pro.property.quality.api;
import lombok.Getter;import lombok.Setter;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
/** 转报修由当前应用的关联设置选择目标，报修表单按目标项目实时配置填写。 */
@Getter @Setter public class QualityTransferRequest { @NotNull @Valid private CreateRepairRequest request; }
