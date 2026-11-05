package org.jetlinks.pro.property.finance.command;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.report.*;

import reactor.core.publisher.Mono;

import java.io.Serial;

/**
 * 合同六项余额的账单下钻，沿用摘要筛选、权限及计算口径；metric取Amounts.METRICS中的公开字段名。
 * 本月净实收允许负数，明细保留退款结果；无权或无合同明确失败，不提供部分范围财务合计。
 *
 * @since 2.20.0
 * @see QueryFinanceContractReportCommand
 * @see ContractReportResult.Detail
 */
@Getter
@Setter
public class QueryFinanceContractReportDetailsCommand
        extends AbstractCommand<
                Mono<PagerResult<ContractReportResult.Detail>>,
                QueryFinanceContractReportDetailsCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = QueryFinanceContractReportCommand.SERVICE_ID;
    private ContractReportInput query;
    private String contractId;
    private String metric;
}
