package org.jetlinks.pro.property.finance.contract;

/**
 * 单个来源合同快照；sourceId/projectId/code稳定，deleted仅表示本来源的显式逻辑删除。 客户为上游原标识，历史合同不强制要求当前有效房屋客户关系；面积使用精确文本。
 *
 * @since 2.20.0
 */
public record ContractMirrorInput(
        String sourceId,
        String code,
        String name,
        String projectId,
        String spaceId,
        String fullName,
        String customerId,
        String customerName,
        String startDate,
        String endDate,
        String leaseArea,
        boolean deleted) {}
