package org.jetlinks.pro.property.finance.command;

import java.util.List;
import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import reactor.core.publisher.Mono;

/** Lists issued, enabled project standards under the caller's project read permission. @since 2.20.0 */
public class QueryMeterStandardsCommand extends AbstractCommand<Mono<List<MeterBillingModels.Standard>>, QueryMeterStandardsCommand> {
  public static final String SERVICE_ID = "financeService:meter-billing";
  public String getProjectId() { return getOrNull("projectId", String.class); }
  public void setProjectId(String value) { with("projectId", value); }
}
