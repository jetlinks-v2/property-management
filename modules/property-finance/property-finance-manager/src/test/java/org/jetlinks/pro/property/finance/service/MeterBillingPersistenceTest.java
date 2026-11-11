package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import org.jetlinks.pro.property.finance.command.ProcessMeterBillCommand;
import org.jetlinks.pro.property.finance.command.QueryMeterStandardsCommand;
import org.jetlinks.pro.property.finance.command.MeterBillingCommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Real formula, PostgreSQL transactions, period gate and unique bill keys; only the remote facility/archive boundary is isolated. */
@SpringBootTest(classes = MeterBillingPersistenceTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeterBillingPersistenceTest {
  static final Duration WAIT = Duration.ofSeconds(40);
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { HouseSettingPersistenceTest.database(registry); }
  @Autowired MeterBillingService service;
  @Autowired ReactiveRepository<StandardEntity,String> standards;
  @Autowired ReactiveRepository<SubjectEntity,String> subjects;
  @Autowired ReactiveRepository<ProjectSubjectEntity,String> issued;
  @Autowired ReactiveRepository<ReceivableEntity,String> bills;
  @Autowired ReactiveRepository<ReceivableLogEntity,String> logs;
  @Autowired ReactiveRepository<FinanceCompanyEntity,String> companies;
  @Autowired HouseArchiveSource archive;
  @Autowired OwnerHouseSource ownerHouses;
  @Autowired OwnerFinanceService ownerFinance;
  @Autowired FinancialPeriodTestSupport periods;
  HouseSettingPersistenceTest fixture;
  MeterUsageEvidence evidence;
  @BeforeEach void setup() {
    fixture = new HouseSettingPersistenceTest(); fixture.standards = standards; fixture.subjectRows = subjects;
    fixture.subjects = issued; fixture.archive = archive; fixture.setup();
    var standard = standards.findById(fixture.standardId).block(WAIT);
    standard.setFormulaExpression("${STANDARD_PRICE} * ${VOLUME}"); standard.setFormulaDisplay("单价 × 用量");
    standard.setVariablesSnapshotJson("[{\"variableCode\":\"STANDARD_PRICE\",\"variableName\":\"单价\"},{\"variableCode\":\"VOLUME\",\"variableName\":\"用量\"}]");
    standard.setPrice(new BigDecimal("3.25")); standards.updateById(standard.getId(), standard).block(WAIT);
    if (companies.findById("company").block(WAIT) == null) {
      var company = new FinanceCompanyEntity(); company.setId("company"); company.setCompanyName("计费测试物业");
      company.setTaxNumber("915001000000000991"); company.setAddress("测试地址"); company.setPhone("123456");
      company.setDepositBank("测试银行"); company.setBankAccount("6222991"); company.setDrawer("开票员");
      company.setReviewer("复核员"); company.setEstateNo("001"); StandardPersistenceTest.initialize(company);
      companies.insert(company).block(WAIT);
    }
    evidence = new MeterUsageEvidence("device", "cloud-device", "101水表", fixture.project, "room", "water", "2024-02",
      fixture.subjectId, fixture.standardId, "VOLUME", Map.of(), new BigDecimal("12.346"), "readings-v1", 1L, "2024-01-01", null);
    ((EvidenceSource)service).setEvidence(evidence);
    ((EvidenceSource)service).setUnavailable(false);
  }
  Context allowed(String... actions) {
    var context = fixture.allowed(); var auth = (SimpleAuthentication)context.get(Authentication.class);
    auth.setPermissions(List.of(SimplePermission.builder().id("facility-meter-billing").actions(Set.of(actions)).build()));
    return context.putAll(TenantHolder.makeCurrent("meter-test-tenant"));
  }
  ProcessMeterBillCommand command(boolean confirm, String fingerprint) {
    var c = new ProcessMeterBillCommand(); c.setDeviceId("device"); c.setMonth("2024-02"); c.setReceivableDate("2024-03-01");
    c.setDueDate("2024-03-20"); c.setConfirm(confirm); c.setFingerprint(fingerprint); return c;
  }
  @Test void previewWritesNothingAndConfirmationAndRetryPublishOneUnpaidReceivableForOwner() {
    var auth = allowed("query","review");
    var preview = service.process(command(false,null)).contextWrite(auth).block(WAIT);
    assertEquals(new BigDecimal("40.12"), preview.amount()); assertNull(preview.billId());
    assertEquals(0, bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
    var written = service.process(command(true,preview.fingerprint())).contextWrite(auth).block(WAIT);
    // A disabled or edited facility rule cannot hide an already committed financial fact.
    ((EvidenceSource)service).setUnavailable(true);
    var repeated = service.process(command(true,preview.fingerprint())).contextWrite(auth).block(WAIT);
    assertEquals(written.billId(), repeated.billId()); assertEquals(written.billNo(), repeated.billNo());
    var bill = bills.findById(written.billId()).block(WAIT);
    var frozen=com.alibaba.fastjson.JSON.parseObject(bill.getSnapshotJson());
    assertEquals("device",frozen.getJSONObject("evidence").getString("deviceId"));
    assertEquals("101水表",frozen.getJSONObject("evidence").getString("deviceName"));
    assertEquals("readings-v1",frozen.getJSONObject("evidence").getString("sourceFingerprint"));
    assertEquals("room",frozen.getJSONObject("house").getString("spaceId"));
    assertEquals("APPROVED",bill.getReviewState()); assertEquals("METER",bill.getSourceType());
    assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),bill.getReviewedOn());
    assertEquals(((Authentication)auth.get(Authentication.class)).getUser().getId(),bill.getReviewedBy());
    assertEquals(new BigDecimal("40.12"),bill.getArrears()); assertEquals(0,bill.getPaidAmount().signum());
    assertEquals("2024-02",bill.getAccountPeriod()); assertEquals("2024-03-01",bill.getReceivableDate());
    assertEquals("customer",bill.getCustomerId()); assertEquals("meter-test-tenant",bill.getTenantId());
    assertEquals(1,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
    assertEquals(1,logs.createQuery().where("receivableId",bill.getId()).count().block(WAIT));
    when(ownerHouses.require(fixture.project,"room")).thenReturn(Mono.just(
      new org.jetlinks.pro.property.base.archive.PropertyOwnerHouse("项目",fixture.context("room"),List.of())));
    var ownerBills=ownerFinance.bills(fixture.project,"room",false,0,20).contextWrite(auth).block(WAIT);
    assertEquals(1,ownerBills.total()); assertEquals("40.12",ownerBills.totalAmount());
    assertEquals(bill.getId(),ownerBills.data().get(0).id());
    assertEquals("40.12",ownerBills.data().get(0).arrears());
  }
  @Test void changedStandardOrMissingPermissionCannotCommitAPreview() {
    var preview = service.process(command(false,null)).contextWrite(allowed("query","review")).block(WAIT);
    StepVerifier.create(service.process(command(true,preview.fingerprint())).contextWrite(allowed("query")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,403)).verify(WAIT);
    standards.createUpdate().set("price",new BigDecimal("4.00")).where("id",fixture.standardId).execute().block(WAIT);
    StepVerifier.create(service.process(command(true,preview.fingerprint())).contextWrite(allowed("query","review")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    assertEquals(0,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
  }
  @Test void builtinTieredWaterUsesRealFormulaPersistsSnapshotAndRejectsWrongMeterType() {
    var standard=standards.findById(fixture.standardId).block(WAIT);
    String expression="TIERED(${STANDARD_PRICE}*${WATER_USAGE},10,4,20,5)*0.9+2";
    standard.setBillingMethod("WATER"); standard.setFormulaExpression(expression); standard.setFormulaDisplay("月度阶梯水费");
    standard.setVariablesSnapshotJson("[{\"variableCode\":\"STANDARD_PRICE\",\"variableName\":\"单价\"},{\"variableCode\":\"WATER_USAGE\",\"variableName\":\"用水量\"}]");
    standard.setPrice(new BigDecimal("3.00")); standards.updateById(standard.getId(),standard).block(WAIT);
    var measured=new MeterUsageEvidence("device","cloud-device","水表",fixture.project,"room","water","2024-02",
      fixture.subjectId,fixture.standardId,"WATER_USAGE",Map.of(),new BigDecimal("12"),"tier-v1",1L,"2024-01-01",null);
    ((EvidenceSource)service).setEvidence(measured);
    var auth=allowed("query","review");
    var options=service.standards(fixture.project).contextWrite(auth).block(WAIT);
    assertTrue(options.get(0).variables().stream().anyMatch(v->v.code().equals("WATER_USAGE")&&v.system()));
    var preview=service.process(command(false,null)).contextWrite(auth).block(WAIT);
    assertEquals(new BigDecimal("36.20"),preview.amount());
    assertEquals(3,preview.detail().tiers().size());
    assertEquals(new BigDecimal("30.00"),preview.detail().tiers().get(0).amount().setScale(2));
    assertEquals(new BigDecimal("8.00"),preview.detail().tiers().get(1).amount().setScale(2));
    var result=service.process(command(true,preview.fingerprint())).contextWrite(auth).block(WAIT);
    var bill=bills.findById(result.billId()).block(WAIT);
    assertEquals(new BigDecimal("36.20"),bill.getActualReceivableAmount());
    assertTrue(bill.getSnapshotJson().contains(expression));
    standards.createUpdate().set("price",new BigDecimal("99")).where("id",standard.getId()).execute().block(WAIT);
    assertEquals(new BigDecimal("36.20"),service.process(command(false,null)).contextWrite(auth).block(WAIT).amount());
    ((EvidenceSource)service).setEvidence(new MeterUsageEvidence("other","other-cloud","电表",fixture.project,"room","electricity","2024-02",
      fixture.subjectId,fixture.standardId,"WATER_USAGE",Map.of(),BigDecimal.ONE,"wrong-type",1L,"2024-01-01",null));
    StepVerifier.create(service.process(command(false,null)).contextWrite(auth))
      .expectErrorMatches(e->TaxItemPersistenceTest.status(e,400)).verify(WAIT);
    assertEquals(1,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
  }
  @Test void evidenceVersionChangeWithSameQuantityInvalidatesApprovalAndReadoutsAreFrozen() {
    var auth=allowed("query","review");
    var first=service.process(command(false,null)).contextWrite(auth).block(WAIT);
    var changed=new MeterUsageEvidence(evidence.deviceId(),evidence.cloudDeviceId(),evidence.deviceName(),evidence.projectId(),
      evidence.spaceId(),evidence.energyType(),evidence.month(),evidence.subjectId(),evidence.standardId(),evidence.usageVariable(),
      evidence.fixedValues(),evidence.quantity(),"new-readings-version",evidence.allocationSince(),evidence.effectiveFrom(),evidence.effectiveTo(),
      List.of(new MeterUsageEvidence.Readout(new BigDecimal("100"),new BigDecimal("100"),1706745600000L),
        new MeterUsageEvidence.Readout(new BigDecimal("112.346"),new BigDecimal("112.346"),1709251200000L)));
    ((EvidenceSource)service).setEvidence(changed);
    StepVerifier.create(service.process(command(true,first.fingerprint())).contextWrite(auth))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    assertEquals(0,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
    var refreshed=service.process(command(false,null)).contextWrite(auth).block(WAIT);
    assertNotEquals(first.fingerprint(),refreshed.fingerprint());
    var posted=service.process(command(true,refreshed.fingerprint())).contextWrite(auth).block(WAIT);
    assertEquals(2,posted.detail().readings().size());
    assertEquals(new BigDecimal("112.346"),posted.detail().readings().get(1).value());
    assertEquals("new-readings-version",com.alibaba.fastjson.JSON.parseObject(bills.findById(posted.billId()).block(WAIT).getSnapshotJson())
      .getJSONObject("evidence").getString("sourceFingerprint"));
  }
  @Test void concurrentConfirmationsReturnOneBillAndClosedMonthRejectsNewBills() {
    var auth = allowed("query","review");
    var preview = service.process(command(false,null)).contextWrite(auth).block(WAIT);
    var replies = Flux.merge(service.process(command(true,preview.fingerprint())),service.process(command(true,preview.fingerprint())))
      .collectList().contextWrite(auth).block(WAIT);
    assertEquals(1,replies.stream().map(r -> r.billId()).distinct().count());
    assertEquals(1,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
    periods.close(fixture.project, LocalDate.of(2024,3,1)).contextWrite(auth).block(WAIT);
    ((EvidenceSource)service).setEvidence(new MeterUsageEvidence("other","other-cloud","102水表",fixture.project,"room","water","2024-02",
      fixture.subjectId,fixture.standardId,"VOLUME",Map.of(),BigDecimal.ONE,"v1",1L,"2024-01-01",null));
    var other = service.process(command(false,null)).contextWrite(auth).block(WAIT);
    StepVerifier.create(service.process(command(true,other.fingerprint())).contextWrite(auth))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    assertEquals(1,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
  }
  @Test void registeredFinanceCommandsAcceptContainerParametersAndPreserveCallerPermissions() {
    var c = new QueryMeterStandardsCommand(); c.setProjectId(fixture.project);
    assertEquals(fixture.project,c.getOrNull("projectId",String.class));
    var options = CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(c)).contextWrite(allowed("query")).block(WAIT);
    assertEquals(fixture.standardId,options.get(0).id());
    var previewCommand = command(false,null);
    assertEquals("2024-02",previewCommand.getOrNull("month",String.class));
    var preview = CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(previewCommand)).contextWrite(allowed("query")).block(WAIT);
    var confirm = command(true,preview.fingerprint());
    assertTrue(confirm.getOrNull("confirm",Boolean.class)); assertEquals(preview.fingerprint(),confirm.getOrNull("fingerprint",String.class));
    StepVerifier.create(CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(confirm)).contextWrite(allowed("query")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,403)).verify(WAIT);
  }
  @Test void publishedLedgerUsesFrozenFactsAndFiltersTenantProjectAndSearchBeforePaging() {
    var auth=allowed("query","review");
    var preview=service.process(command(false,null)).contextWrite(auth).block(WAIT);
    var saved=service.process(command(true,preview.fingerprint())).contextWrite(auth).block(WAIT);
    ((EvidenceSource)service).setUnavailable(true);
    var query=new org.jetlinks.pro.property.finance.command.QueryMeterBillsCommand(); query.setProjectId(fixture.project);
    var terms=org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().where("accountPeriod","2024-02").getParam();
    terms.setPageSize(1); query.setQuery(terms);
    var page=CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(query)).contextWrite(allowed("query")).block(WAIT);
    assertEquals(1,page.getTotal()); assertEquals(saved.billId(),page.getData().get(0).id());
    assertEquals(new BigDecimal("40.12"),page.getData().get(0).result().amount());
    assertEquals("2024-03-20",page.getData().get(0).result().detail().dueDate());
    query.setQuery(org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().where("accountPeriod","2023-01").getParam());
    assertEquals(0,CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(query)).contextWrite(allowed("query")).block(WAIT).getTotal());
    query.setQuery(terms);
    assertEquals(0,CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(query)).contextWrite(allowed("query").putAll(TenantHolder.makeCurrent("other-tenant"))).block(WAIT).getTotal());
  }
  @Test void multipleCustomersNewOwnerAndExpiredStandardAreRejectedWithoutAnInvoice() {
    var original=fixture.context("room");
    when(archive.context(eq(fixture.project),eq("room"),anyBoolean()))
      .thenReturn(Mono.just(new org.jetlinks.pro.property.base.archive.PropertyHouseContext(original.house(),List.of())));
    StepVerifier.create(service.process(command(false,null)).contextWrite(allowed("query")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    var c=original.customers().get(0);
    long midMonth=LocalDate.of(2024,2,15).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    var changed=new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(c.relationshipId(),c.customerId(),c.userId(),
      c.customerType(),c.name(),c.telephone(),c.nature(),midMonth,c.relationshipVersion(),c.customerVersion());
    when(archive.context(eq(fixture.project),eq("room"),anyBoolean()))
      .thenReturn(Mono.just(new org.jetlinks.pro.property.base.archive.PropertyHouseContext(original.house(),List.of(changed))));
    StepVerifier.create(service.process(command(false,null)).contextWrite(allowed("query")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    when(archive.context(eq(fixture.project),eq("room"),anyBoolean())).thenReturn(Mono.just(original));
    standards.createUpdate().set("cancelDate","2024-02-15").where("id",fixture.standardId).execute().block(WAIT);
    StepVerifier.create(service.process(command(false,null)).contextWrite(allowed("query")))
      .expectErrorMatches(e -> TaxItemPersistenceTest.status(e,409)).verify(WAIT);
    assertEquals(0,bills.createQuery().where("projectId",fixture.project).count().block(WAIT));
  }
  interface EvidenceSource { void setEvidence(MeterUsageEvidence evidence); void setUnavailable(boolean value); }
  static class TestBilling extends MeterBillingService implements EvidenceSource {
    private MeterUsageEvidence evidence;
    private boolean unavailable;
    TestBilling(FinanceScope scope, CollectionStandardSource source, ReactiveRepository<StandardEntity,String> standards,
      ReactiveRepository<ReceivableEntity,String> bills, ReactiveRepository<ReceivableLogEntity,String> logs, FinancialPeriodGate periods,
      HouseArchiveSource houses, MeterBillingRules rules, InvoiceCompanyService companies) {
      super(scope,source,standards,bills,logs,periods,houses,rules,companies);
    }
    public void setEvidence(MeterUsageEvidence e) { evidence=e; }
    public void setUnavailable(boolean value) { unavailable=value; }
    @Override Mono<MeterUsageEvidence> evidence(ProcessMeterBillCommand c) { return unavailable ? Mono.error(new IllegalStateException("rule disabled")) : Mono.just(evidence); }
    @Override Mono<MeterUsageEvidence> identity(ProcessMeterBillCommand c) { return Mono.just(evidence); }
  }
  @Configuration @Import({HouseSettingPersistenceTest.Config.class,CollectionStandardSource.class,MeterBillingRules.class,MeterBillLedger.class,
    MeterBillingCommandSupport.class,CommandServiceEndpointRegister.class,OwnerFinanceService.class})
  static class Config {
    @Bean OwnerHouseSource ownerHouses() { return mock(OwnerHouseSource.class); }
    @Bean MeterBillingService billing(FinanceScope scope, CollectionStandardSource source, ReactiveRepository<StandardEntity,String> standards,
      ReactiveRepository<ReceivableEntity,String> bills, ReactiveRepository<ReceivableLogEntity,String> logs, FinancialPeriodGate periods,
      HouseArchiveSource houses, MeterBillingRules rules, InvoiceCompanyService companies) {
      return new TestBilling(scope,source,standards,bills,logs,periods,houses,rules,companies);
    }
  }
}
