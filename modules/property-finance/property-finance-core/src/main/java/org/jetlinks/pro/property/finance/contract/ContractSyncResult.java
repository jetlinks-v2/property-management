package org.jetlinks.pro.property.finance.contract;

/** 原子增量应用结果；未出现的合同不删除，无变更不会提升同步版本。 @since 2.20.0 */
public record ContractSyncResult(
        long version, int added, int updated, int deleted, int unchanged) {}
