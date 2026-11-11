package org.jetlinks.pro.property.finance.command;

import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import reactor.core.publisher.Mono;

/** Finance-owned published meter ledger. Project, tenant and space scope are checked before paging. @since 2.20.0 */
public class QueryMeterBillsCommand extends AbstractCommand<Mono<PagerResult<MeterBillingModels.Bill>>, QueryMeterBillsCommand> {
  public String getProjectId() { return getOrNull("projectId",String.class); }
  public void setProjectId(String value) { with("projectId",value); }
  public QueryParamEntity getQuery() { return getOrNull("query",QueryParamEntity.class); }
  public void setQuery(QueryParamEntity value) { with("query",value); }
}
