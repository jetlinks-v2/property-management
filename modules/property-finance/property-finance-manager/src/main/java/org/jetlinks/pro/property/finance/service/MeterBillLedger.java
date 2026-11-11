package org.jetlinks.pro.property.finance.service;

import com.alibaba.fastjson.JSON;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** Reads frozen financial facts without depending on a still-active facility rule. */
@Service @RequiredArgsConstructor
public class MeterBillLedger {
  private final FinanceScope scope;
  private final ReactiveRepository<ReceivableEntity,String> bills;

  public Mono<PagerResult<Bill>> query(String project, QueryParamEntity input) {
    return scope.authenticated().doOnNext(auth -> {
      if (!auth.hasPermission("facility-meter-billing","query")) throw FinanceScope.error("permission_denied",403);
    }).then(scope.project(project,CrudAssetPermission.read))
      .then(TenantHolder.currentId().switchIfEmpty(Mono.error(FinanceScope.error("permission_denied",403))))
      .flatMap(tenant -> {
        var q = SubjectService.page(input == null ? new QueryParamEntity() : input);
        q.setForUpdate(false);
        q.setTerms(energyTerms(q.getTerms()));
        // User OR terms remain inside their own group; published facts cannot escape the requested tenant/project.
        q.toNestQuery(n -> n.and("tenantId",tenant).and("projectId",project).and("deleted",false)
          .and("sourceType","METER").and("reviewState","APPROVED"));
        if(q.getSorts().isEmpty()) q.toQuery().orderByDesc("accountPeriod").orderByDesc("id");
        return FinanceSpaceQuery.inject(q,bills,CrudAssetPermission.read)
          .flatMap(secured -> QueryHelper.transformPageResult(QueryHelper.queryPager(secured,bills::createQuery),
            rows -> Mono.just(rows.stream().map(MeterBillLedger::view).toList())));
      });
  }

  /** 分类取落账时的能源快照；旧账缺少证据类型时回退到同一快照的计费方式，在分页前过滤。 */
  private static List<Term> energyTerms(List<Term> input) {
    var result = new ArrayList<Term>();
    for (var term : input) {
      if ("energyType".equals(term.getColumn())) {
        String energy = Objects.toString(term.getValue(), "");
        if (!"eq".equals(term.getTermType()) || !Set.of("water", "electricity", "gas").contains(energy) || !term.getTerms().isEmpty())
          throw FinanceScope.error("report_query_invalid", 400);
        var filter = SqlTerm.of("coalesce(cast(snapshot_json as jsonb)->'evidence'->>'energyType',"
          + " case cast(snapshot_json as jsonb)->'standard'->>'billingMethod'"
          + " when 'WATER' then 'water' when 'ELECTRICITY' then 'electricity' when 'GAS' then 'gas' end) = ?", energy);
        filter.setType(term.getType()); result.add(filter);
      } else {
        term.setTerms(energyTerms(term.getTerms())); result.add(term);
      }
    }
    return result;
  }

  static Detail detail(ReceivableEntity row) {
    var snapshot=JSON.parseObject(row.getSnapshotJson());
    var standard=snapshot.getJSONObject("standard");
    var values=new TreeMap<String,java.math.BigDecimal>();
    var stored=snapshot.getJSONObject("values");
    if(stored!=null) for(String key:stored.keySet()) values.put(key,stored.getBigDecimal(key));
    var readouts=snapshot.getJSONArray("meterReadings");
    return new Detail(row.getReceivableDate(),row.getDueDate(),standard==null?null:standard.getString("formulaDisplay"),
      standard==null?null:standard.getBigDecimal("price"),values,
      readouts==null?List.of():readouts.stream().map(value -> {
        var reading=JSON.parseObject(JSON.toJSONString(value));
        return new Readout(reading.getBigDecimal("value"),reading.getBigDecimal("rawValue"),reading.getLong("time"));
      }).toList(),row.getReviewedName(),row.getCreateTime(),
      standard==null?List.of():tiers(standard.getString("formulaExpression"),values));
  }

  static List<Tier> tiers(String expression,Map<String,java.math.BigDecimal> values) {
    if(expression==null || !expression.contains("TIERED(")) return List.of();
    return new org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser().parse(expression,values.keySet())
      .tierCharges(values).stream().map(t -> new Tier(t.from(),t.to(),t.price(),t.quantity(),t.amount())).toList();
  }

  static Bill view(ReceivableEntity row) {
    var snapshot=JSON.parseObject(row.getSnapshotJson());
    var e=snapshot.getJSONObject("evidence");
    var standard=snapshot.getJSONObject("standard");
    String type=e==null?null:e.getString("energyType");
    if(type==null && standard!=null) type=switch(Objects.toString(standard.getString("billingMethod"),"")) {
      case "WATER" -> "water"; case "ELECTRICITY" -> "electricity"; case "GAS" -> "gas"; default -> null;
    };
    var result=new Result(e==null?null:e.getString("deviceId"),e==null?null:e.getString("deviceName"),row.getProjectId(),row.getSpaceId(),
      row.getFullName(),row.getCustomerName(),type,row.getAccountPeriod(),snapshot.getBigDecimal("quantity"),
      row.getContractReceivableAmount(),row.getSubjectName(),row.getStandardName(),snapshot.getString("fingerprint"),
      row.getId(),row.getBillNo(),detail(row));
    return new Bill(row.getId(),row.getAccountPeriod(),row.getFullName(),row.getCustomerName(),row.getSubjectName(),
      row.getBillNo(),row.getReceivableDate(),row.getDueDate(),result);
  }
}
