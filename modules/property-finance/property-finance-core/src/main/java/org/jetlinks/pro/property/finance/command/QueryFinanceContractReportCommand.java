package org.jetlinks.pro.property.finance.command;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.report.*;

import reactor.core.publisher.Mono;

import java.io.Serial;

/**
 * 查询项目合同月报的公开只读边界。需要property-finance-report-contract:query及项目/参与房屋read；
 * 保留调用者认证、租户和资产范围，结果金额为精确十进制文本，超过处理上限明确拒绝。
 *
 * @since 2.20.0
 * @see ContractReportInput
 * @see ContractReportResult
 */
@Getter
@Setter
public class QueryFinanceContractReportCommand
        extends AbstractCommand<Mono<ContractReportResult>, QueryFinanceContractReportCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:finance-contract-report";
    private ContractReportInput query;
}
