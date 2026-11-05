package org.jetlinks.pro.property.finance.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.finance.contract.ContractSyncResult;
import org.jetlinks.pro.property.finance.service.ContractMirrorService;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 财务合同镜像公开写边界；服务内独立检查按钮、项目/房屋资产及租户，不绕过调用者权限。 @since 2.20.0 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = ApplyFinanceContractsCommand.SERVICE_ID, name = "物业财务合同服务")
public class FinanceContractCommandSupport {
    private final ContractMirrorService contracts;

    @CommandHandler(name = "应用合同增量", description = "预期版本校验及整批事务应用；缺失项不删除")
    public Mono<ContractSyncResult> applyContracts(ApplyFinanceContractsCommand command) {
        return contracts.apply(
                command.getProjectId(), command.getExpectedVersion(), command.getContracts());
    }
}
