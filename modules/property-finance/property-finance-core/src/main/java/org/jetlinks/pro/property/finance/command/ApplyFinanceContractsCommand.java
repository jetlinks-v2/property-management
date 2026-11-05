package org.jetlinks.pro.property.finance.command;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.contract.*;

import reactor.core.publisher.Mono;

import java.io.Serial;
import java.util.List;

/**
 * 合同来源增量公开写边界。调用方须拥有property-finance-contracts:sync及项目/全部新旧房屋save。
 * 保留调用者租户和资产范围，不提供免认证身份；expectedVersion来自下载前读取的本地同步版本， 首次为null或-1，冲突409要求重新拉取。任一行失败整批回滚，不按缺失行推断删除。
 *
 * @since 2.20.0
 * @see ContractSyncResult
 */
@Getter
@Setter
public class ApplyFinanceContractsCommand
        extends AbstractCommand<Mono<ContractSyncResult>, ApplyFinanceContractsCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:finance-contract";
    private String projectId;
    private Long expectedVersion;
    private List<ContractMirrorInput> contracts;
}
