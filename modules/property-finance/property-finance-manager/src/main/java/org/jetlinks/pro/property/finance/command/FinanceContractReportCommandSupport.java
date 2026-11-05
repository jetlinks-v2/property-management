package org.jetlinks.pro.property.finance.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.finance.report.ContractReportResult;
import org.jetlinks.pro.property.finance.service.ContractReportService;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 合同月报公开读边界；查询服务独立检查按钮与资产权限，不借用同步权限或后台身份。 @since 2.20.0 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryFinanceContractReportCommand.SERVICE_ID, name = "物业合同报表服务")
public class FinanceContractReportCommandSupport {
    private final ContractReportService reports;

    @CommandHandler(name = "查询合同月报", description = "完整筛选范围摘要与分页结果，同一只读快照")
    public Mono<ContractReportResult> queryReport(QueryFinanceContractReportCommand command) {
        return reports.query(command.getQuery());
    }

    @CommandHandler(name = "查询合同账单明细", description = "六项余额的非零账单下钻，保留负数净实收")
    public Mono<PagerResult<ContractReportResult.Detail>> queryDetails(
            QueryFinanceContractReportDetailsCommand command) {
        return reports.details(command.getQuery(), command.getContractId(), command.getMetric());
    }
}
