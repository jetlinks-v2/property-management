package org.jetlinks.pro.property.base.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前物业管理请求的登录与项目上下文，仅返回调用方自己的最小身份信息。
 * projectId 为空表示尚未选择项目；该信息不替代业务数据的资产权限校验。
 */
@Schema(description = "当前物业管理上下文")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PropertyContext(
    @Schema(description = "平台用户 ID") String userId,
    @Schema(description = "平台登录名") String username,
    @Schema(description = "当前项目 ID，未选择时为 null", nullable = true) String projectId
) {
}
