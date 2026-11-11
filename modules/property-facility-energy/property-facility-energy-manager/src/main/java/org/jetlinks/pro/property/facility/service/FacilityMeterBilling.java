package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.finance.command.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Published facts come from finance; monthly review queries stay correlated to device assets. */
@Service @RequiredArgsConstructor
public class FacilityMeterBilling {
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final MeterBillStore bills;
  private final ProcessStore processes;
  private final FacilityMeterDrafts drafts;

  public Mono<PagerResult<MeterBillingModels.Bill>> ledger(String project,QueryParamEntity input) {
    var command=new QueryMeterBillsCommand(); command.setProjectId(project); command.setQuery(input);
    return scope.current().then(records.assertProject(project,CrudAssetPermission.read))
      .then(CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
        .flatMap(s -> s.execute(command)));
  }
  public Mono<MeterBillingModels.Bill> published(String project,String id) {
    var q=QueryParamEntity.newQuery().where("id",id).getParam();
    return ledger(project,q).flatMap(page -> Mono.justOrEmpty(page.getData().stream().findFirst()))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.NotFoundException()));
  }
  public Mono<PagerResult<MeterBillEntity>> reviews(String project,String state,QueryParamEntity input) {
    FacilityScope.require(Set.of("pending","approved","rejected","error","all").contains(state),"invalid_state");
    var q=(input==null?new QueryParamEntity():input).clone(); q.setPaging(true); q.setForUpdate(false);
    q.toNestQuery(n -> {
      n.and("projectId",project);
      if("error".equals(state)) n.in("state",List.of("error","failed","processing"));
      else if(!"all".equals(state)) n.and("state",state);
    });
    return records.assertProject(project,CrudAssetPermission.read)
      .then(scope.query(q,FacilityAssetType.facilityDevice,"deviceId")).flatMap(bills::queryPager);
  }
  public Mono<MeterBillEntity> detail(String id) { return drafts.read(id,CrudAssetPermission.read); }
  public Mono<PagerResult<ProcessEntity>> logs(String id,QueryParamEntity input) {
    return detail(id).then(scope.applicationQuery(input)).flatMap(q -> {
      q.toNestQuery(n -> n.and("targetId",id).and("targetType","meterBill"));
      return processes.queryPager(q);
    });
  }
}
