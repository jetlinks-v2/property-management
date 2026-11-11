package org.jetlinks.pro.property.finance.command;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.jetlinks.pro.property.finance.service.MeterBillingService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Public finance boundary; service repeats action/project/space checks and never trusts frontend amounts. @since 2.20.0 */
@Component @RequiredArgsConstructor @Authorize
@CommandService(id = QueryMeterStandardsCommand.SERVICE_ID, name = "水电计费")
public class MeterBillingCommandSupport {
  private final MeterBillingService billing;
  private final org.jetlinks.pro.property.finance.service.MeterBillLedger ledger;
  @CommandHandler
  public Mono<org.hswebframework.web.api.crud.entity.PagerResult<MeterBillingModels.Bill>> queryMeterBills(QueryMeterBillsCommand command) {
    return ledger.query(command.getProjectId(),command.getQuery());
  }
  @CommandHandler
  public Mono<List<MeterBillingModels.Standard>> queryMeterStandards(QueryMeterStandardsCommand command) { return billing.standards(command.getProjectId()); }
  @CommandHandler
  public Mono<MeterBillingModels.Result> processMeterBill(ProcessMeterBillCommand command) { return billing.process(command); }
}
