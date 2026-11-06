package org.jetlinks.pro.property.finance.contract;

/** 授权后的合同镜像；名称与来源标识均显式保留，不返回源URL或认证载荷。 @since 2.20.0 */
public record ContractMirrorView(
        String id,
        String sourceId,
        String projectId,
        String code,
        String name,
        String spaceId,
        String fullName,
        String customerId,
        String customerName,
        String startDate,
        String endDate,
        String leaseArea,
        Long version) {}
