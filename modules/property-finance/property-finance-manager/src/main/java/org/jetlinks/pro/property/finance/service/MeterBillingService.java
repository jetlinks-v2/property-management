package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import java.math.*;
import java.time.*;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.facility.command.QueryFacilityMeterUsageCommand;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import org.jetlinks.pro.property.finance.command.ProcessMeterBillCommand;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels.*;
import org.jetlinks.pro.property.finance.payment.FinanceDocumentNumbers;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.jetlinks.pro.property.finance.standard.StandardSnapshots;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** Remote evidence is re-read; the finance period lock serializes duplicate writes without cross-database transactions. */
@Service @RequiredArgsConstructor
public class MeterBillingService {
  private final FinanceScope scope;
  private final CollectionStandardSource source;
  private final ReactiveRepository<StandardEntity, String> standards;
  private final ReactiveRepository<ReceivableEntity, String> bills;
  private final ReactiveRepository<ReceivableLogEntity, String> logs;
  private final FinancialPeriodGate periods;
  private final HouseArchiveSource houses;
  private final MeterBillingRules rules;
  private final InvoiceCompanyService companies;

  public Mono<List<Standard>> standards(String project) {
    return authorize().then(scope.project(project, CrudAssetPermission.read)).thenMany(source.issuedSubjects(project))
      .concatMap(subject -> standards.createQuery().where("projectId", project).and("subjectId", subject.getId())
        .and("deleted", false).fetch().map(s -> new Standard(s.getId(), s.getVersion(), s.getSubjectId(), s.getSubjectName(),
          s.getStandardName(), s.getFormulaDisplay(), s.getPrice(), s.getExecuteDate(), s.getCancelDate(),
          StandardSnapshots.variables(s.getVariablesSnapshotJson()).stream().map(v -> new Variable(v.variableCode(), v.variableName(),
            MeterBillingRules.SYSTEM.contains(v.variableCode()))).toList())))
      .take(2001).collectList().doOnNext(rows -> { if (rows.size() > 2000) throw error("meter_too_many_standards", 400); });
  }
  private Mono<Authentication> authorize() {
    return scope.authenticated().flatMap(a -> a.hasPermission("facility-meter-billing", "query") ? Mono.just(a) : Mono.error(error("permission_denied", 403)));
  }
  Mono<MeterUsageEvidence> evidence(ProcessMeterBillCommand input) {
    var command = new QueryFacilityMeterUsageCommand(); command.setDeviceId(input.getDeviceId()); command.setMonth(input.getMonth());
    return CommandSupportManagerProviders.getCommandSupport(QueryFacilityMeterUsageCommand.SERVICE_ID).flatMap(s -> s.execute(command));
  }
  Mono<MeterUsageEvidence> identity(ProcessMeterBillCommand input) {
    var command = new QueryFacilityMeterUsageCommand(); command.setDeviceId(input.getDeviceId()); command.setMonth(input.getMonth()); command.setIdentityOnly(true);
    return CommandSupportManagerProviders.getCommandSupport(QueryFacilityMeterUsageCommand.SERVICE_ID).flatMap(s -> s.execute(command));
  }
  @Transactional
  public Mono<Result> process(ProcessMeterBillCommand input) {
    return authorize().flatMap(auth -> {
      if (input.isConfirm() && !auth.hasPermission("facility-meter-billing", "review")) return Mono.error(error("permission_denied", 403));
      YearMonth month;
      LocalDate due, receivable;
      try { month = YearMonth.parse(input.getMonth()); due = LocalDate.parse(input.getDueDate()); receivable = LocalDate.parse(input.getReceivableDate()); }
      catch (RuntimeException invalid) { return Mono.error(error("meter_period_invalid", 400)); }
      if (due.isBefore(receivable)) return Mono.error(error("meter_period_invalid", 400));
      return identity(input).flatMap(e -> scope.project(e.projectId(), input.isConfirm() ? CrudAssetPermission.save : CrudAssetPermission.read)
        .then(TenantHolder.currentId().switchIfEmpty(Mono.error(error("permission_denied", 403))))
        .flatMap(tenant -> {
          String key = MeterBillingRules.key(tenant, e.projectId(), e.cloudDeviceId(), e.month());
          // The per-project period lock is acquired before checking uniqueness, so concurrent confirmations return one bill.
          Mono<Void> lock = input.isConfirm() ? periods.lock(e.projectId()).then() : Mono.empty();
          return lock.then(bills.createQuery().where("billKey", key).and("tenantId", tenant).fetchOne())
            .flatMap(b -> AssetsHolder.assertPermission(AssetType.of("space"),b.getSpaceId(),
              input.isConfirm() ? CrudAssetPermission.save : CrudAssetPermission.read).thenReturn(existing(b,e)))
            .switchIfEmpty(Mono.defer(() -> evidence(input).flatMap(measured -> {
              if (!Objects.equals(e.cloudDeviceId(),measured.cloudDeviceId()) || !Objects.equals(e.projectId(),measured.projectId()))
                return Mono.error(error("meter_preview_changed",409));
              return AssetsHolder.assertPermission(AssetType.of("space"),measured.spaceId(),
                input.isConfirm() ? CrudAssetPermission.save : CrudAssetPermission.read)
                .then(previewOrWrite(input,month,measured,tenant,key,auth,receivable,due));
            })));
        }));
    }).as(MonoTracer.create("property.finance.meter.process"));
  }
  private Mono<Result> previewOrWrite(ProcessMeterBillCommand input, YearMonth month, MeterUsageEvidence evidence,
      String tenant, String key, Authentication auth, LocalDate receivable, LocalDate due) {
    return standards.findById(evidence.standardId()).filter(s -> evidence.projectId().equals(s.getProjectId()) && !Boolean.TRUE.equals(s.getDeleted()))
      .switchIfEmpty(Mono.error(error("setting_standard_unavailable", 409)))
      .flatMap(initial -> (input.isConfirm()
        ? companies.company(initial.getCompanyId(), true)
          .then(source.lock(evidence.projectId(), initial.getCompanyId(), Set.of(initial.getId())))
        : Mono.<Void>empty()).then(source.current(evidence.projectId(), initial.getId(), initial.getCompanyId())))
      .flatMap(s -> {
        if (!Objects.equals(s.getSubjectId(), evidence.subjectId()) ||
          s.getExecuteDate() != null && s.getExecuteDate().compareTo(month.atDay(1).toString()) > 0 ||
          s.getCancelDate() != null && s.getCancelDate().compareTo(month.atEndOfMonth().toString()) < 0) return Mono.error(error("setting_standard_unavailable", 409));
        return houses.context(evidence.projectId(), evidence.spaceId(), input.isConfirm()).flatMap(context -> {
          // 内置用量必须来自相应能源类型，不能把电表读数代入水费公式；旧自定义用量保留。
          if (org.jetlinks.pro.property.finance.meter.MeterBillingModels.isUsageCode(evidence.usageVariable())
              && !Objects.equals(evidence.usageVariable(), org.jetlinks.pro.property.finance.meter.MeterBillingModels.usageCode(evidence.energyType())))
            return Mono.error(error("meter_rule_invalid", 400));
          var customer = customer(context, month);
          var values = rules.values(s, evidence.usageVariable(), evidence.quantity(), evidence.fixedValues(), context.house().billingArea());
          BigDecimal amount = rules.calculate(s, values);
          String fingerprint = DigestUtils.sha256Hex(JSON.toJSONString(Arrays.asList(evidenceSnapshot(evidence), s.getVersion(), s.getFormulaVersion(), s.getFormulaExpression(),
            s.getTaxSnapshotJson(), s.getPrice(), new TreeMap<>(values), SettingRules.houseSnapshot(context.house()),
            context.customers().stream().map(SettingRules::customerSnapshot).toList(), receivable.toString(), due.toString())));
          var detail = new Detail(receivable.toString(),due.toString(),s.getFormulaDisplay(),s.getPrice(),values,
            evidence.readings()==null?List.of():evidence.readings().stream().map(r -> new Readout(r.value(),r.rawValue(),r.time())).toList(),null,null,
            MeterBillLedger.tiers(s.getFormulaExpression(),values));
          var result = result(evidence, context, customer, s, amount, fingerprint, null, null,detail);
          if (!input.isConfirm()) return Mono.just(result);
          if (!fingerprint.equals(input.getFingerprint())) return Mono.error(error("meter_preview_changed", 409));
          return periods.assertOpen(evidence.projectId(), month.atEndOfMonth()).then(periods.assertOpen(evidence.projectId(), receivable))
            .then(Mono.defer(() -> {
              var bill = bill(tenant, key, month, evidence, context, customer, s, values, amount, receivable, due, fingerprint, auth);
              return bills.insert(bill).then(logs.insert(log(bill, auth))).thenReturn(
                result(evidence, context, customer, s, amount, fingerprint, bill.getId(), bill.getBillNo(),MeterBillLedger.detail(bill)));
            }));
        });
      });
  }
  static PropertyHouseCustomer customer(PropertyHouseContext context, YearMonth month) {
    if (context.customers() == null || context.customers().size() != 1) throw error("meter_customer_ambiguous", 409);
    var c = context.customers().get(0);
    long start = month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    if (c.startTime() == null || c.startTime() > start) throw error("meter_customer_changed", 409);
    return c;
  }
  private Result result(MeterUsageEvidence e, PropertyHouseContext context, PropertyHouseCustomer c, StandardEntity s,
      BigDecimal amount, String fingerprint, String billId, String billNo, Detail detail) {
    return new Result(e.deviceId(), e.deviceName(), e.projectId(), e.spaceId(), context.house().fullName(), c.name(), e.energyType(), e.month(),
      e.quantity(), amount, s.getSubjectName(), s.getStandardName(), fingerprint, billId, billNo,detail);
  }
  private Result existing(ReceivableEntity bill, MeterUsageEvidence e) {
    if (Boolean.TRUE.equals(bill.getDeleted())) throw error("meter_bill_deleted", 409);
    var snapshot = JSON.parseObject(bill.getSnapshotJson());
    return new Result(e.deviceId(), e.deviceName(), bill.getProjectId(), bill.getSpaceId(), bill.getFullName(), bill.getCustomerName(), e.energyType(),
      bill.getAccountPeriod(), snapshot.getBigDecimal("quantity"), bill.getContractReceivableAmount(), bill.getSubjectName(), bill.getStandardName(),
      snapshot.getString("fingerprint"), bill.getId(), bill.getBillNo(),MeterBillLedger.detail(bill));
  }
  private ReceivableEntity bill(String tenant, String key, YearMonth month, MeterUsageEvidence e, PropertyHouseContext context,
      PropertyHouseCustomer c, StandardEntity s, Map<String,BigDecimal> values, BigDecimal amount, LocalDate receivable, LocalDate due,
      String fingerprint, Authentication auth) {
    var b = new ReceivableEntity(); b.setId(UUID.randomUUID().toString()); b.setBillNo(FinanceDocumentNumbers.next("YS"));
    b.setBillKey(key); b.setTenantId(tenant); b.setVersion(0L); b.setDeleted(false); b.setCreateTime(System.currentTimeMillis()); b.setModifyTime(b.getCreateTime());
    b.setModifierId(auth.getUser().getId()); b.setModifierName(FinanceScope.operatorName(auth));
    // 确认出账即发布应收；普通应收二次审核已取消，业主端只展示已发布账单。
    b.setReviewState("APPROVED"); b.setReviewedOn(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    b.setReviewedBy(auth.getUser().getId()); b.setReviewedName(FinanceScope.operatorName(auth));
    b.setProjectId(e.projectId()); b.setSpaceId(e.spaceId()); b.setFullName(context.house().fullName());
    b.setSubjectId(s.getSubjectId()); b.setSubjectName(s.getSubjectName()); b.setStandardId(s.getId()); b.setStandardName(s.getStandardName());
    b.setConfigVersion(0); b.setSourceType("METER"); b.setAccountPeriod(month.toString());
    b.setCustomerId(c.customerId()); b.setCustomerRelationshipId(c.relationshipId()); b.setCustomerName(c.name());
    b.setPeriodStart(month.atDay(1).toString()); b.setPeriodEnd(month.atEndOfMonth().toString());
    b.setCalcStartDate(b.getPeriodStart()); b.setCalcEndDate(b.getPeriodEnd()); b.setReceivableDate(receivable.toString()); b.setDueDate(due.toString());
    b.setAccruedAsOfDate(b.getPeriodEnd()); b.setCalculatedAsOfDate(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()); b.setPeriodStatus("CLOSED");
    b.setMoneyScale(s.getChargeRoundType()); b.setFrozen(false); b.setPaymentLocked(false); b.setBankCollectionLocked(false);
    b.setLateFeeLocked(false); b.setFinancialClosed(false); b.setDiscountLocked(false); b.setChargeLocked(false); b.setSplit(false);
    var bean = new org.springframework.beans.BeanWrapperImpl(b);
    for (var field : bean.getPropertyDescriptors()) if (field.getPropertyType() == BigDecimal.class) bean.setPropertyValue(field.getName(), BigDecimal.ZERO.setScale(2));
    b.setContractReceivableAmount(amount); b.setAccruedReceivableAmount(amount); b.setActualReceivableAmount(amount); b.setArrears(amount);
    var snapshot = new LinkedHashMap<String,Object>(); snapshot.put("house", SettingRules.houseSnapshot(context.house())); snapshot.put("customer", SettingRules.customerSnapshot(c));
    snapshot.put("standard", s); snapshot.put("values", values); snapshot.put("evidence", evidenceSnapshot(e)); snapshot.put("quantity", e.quantity());
    snapshot.put("fingerprint", fingerprint); snapshot.put("sourceType", "METER"); b.setSnapshotJson(JSON.toJSONString(snapshot));
    snapshot.put("meterReadings", readingSnapshots(e)); b.setSnapshotJson(JSON.toJSONString(snapshot));
    return b;
  }
  /** Fastjson in the published service does not serialize Java record accessors; explicit maps protect both audit and fingerprints. */
  static Map<String,Object> evidenceSnapshot(MeterUsageEvidence e) {
    var result=new LinkedHashMap<String,Object>();
    result.put("deviceId",e.deviceId()); result.put("cloudDeviceId",e.cloudDeviceId()); result.put("deviceName",e.deviceName());
    result.put("projectId",e.projectId()); result.put("spaceId",e.spaceId()); result.put("energyType",e.energyType()); result.put("month",e.month());
    result.put("subjectId",e.subjectId()); result.put("standardId",e.standardId()); result.put("usageVariable",e.usageVariable());
    result.put("fixedValues",new TreeMap<>(e.fixedValues())); result.put("quantity",e.quantity()); result.put("sourceFingerprint",e.sourceFingerprint());
    result.put("allocationSince",e.allocationSince()); result.put("effectiveFrom",e.effectiveFrom()); result.put("effectiveTo",e.effectiveTo());
    result.put("readings",readingSnapshots(e)); return result;
  }
  private static List<Map<String,Object>> readingSnapshots(MeterUsageEvidence e) {
    if(e.readings()==null) return List.of();
    return e.readings().stream().map(reading -> {
      Map<String,Object> row=new LinkedHashMap<>(); row.put("value",reading.value()); row.put("rawValue",reading.rawValue()); row.put("time",reading.time()); return row;
    }).toList();
  }
  private ReceivableLogEntity log(ReceivableEntity bill, Authentication auth) {
    var l = new ReceivableLogEntity(); l.setId(UUID.randomUUID().toString()); l.setProjectId(bill.getProjectId()); l.setSpaceId(bill.getSpaceId());
    l.setReceivableId(bill.getId()); l.setOperation("METER"); l.setReason("水电计费出账"); l.setOperatorId(auth.getUser().getId());
    l.setOperatorName(FinanceScope.operatorName(auth)); l.setCreateTime(System.currentTimeMillis()); l.setBeforeVersion(0L); l.setAfterVersion(0L);
    l.setBeforeJson("{}"); l.setAfterJson(ReceivableService.auditSnapshot(bill)); return l;
  }
}
