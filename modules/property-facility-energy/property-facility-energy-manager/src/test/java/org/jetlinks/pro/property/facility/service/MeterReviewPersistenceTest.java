package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** Real PostgreSQL versions/JSON snapshots and CAS; remote finance is isolated, not the review lifecycle. */
@SpringBootTest(classes=MeterReviewPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class MeterReviewPersistenceTest {
  static final Duration WAIT=Duration.ofSeconds(40);
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) { FacilityPersistenceTest.database(r); }
  @Autowired MeterBillStore bills;
  @Autowired FacilityMeterReviews reviews;
  @SpyBean FacilityMeterDrafts drafts;
  @SpyBean FacilityRecords records;
  @Autowired FacilityScope scope;
  @Autowired ProcessStore processes;
  @Autowired FacilityMeterBilling billing;
  @Autowired MeterChargeStore charges;
  @Autowired DeviceStore devices;
  @Autowired FacilityMeterMonths months;
  @Autowired FacilityExecution execution;
  DeviceEntity device;
  @BeforeEach void setup() {
    reset(scope,execution);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("meter-tenant","meter-app","reviewer","杜甫")));
    when(scope.assertRecord(any(),any(),anyString(),any())).thenReturn(Mono.empty());
    doReturn(Mono.empty()).when(records).assertProject(anyString(),any());
    when(scope.query(any(),any(),anyString())).thenAnswer(call -> {
      org.hswebframework.web.api.crud.entity.QueryParamEntity q=call.getArgument(0);
      return scope.current().map(c -> FacilityScope.applicationQuery(q,c));
    });
    when(execution.asOwner(any(),any())).thenAnswer(call -> {
      java.util.function.Supplier<Mono<Void>> work=call.getArgument(1); return work.get();
    });
    device=FacilityScope.initialize(new DeviceEntity(),new FacilityContext("meter-tenant","meter-app","owner","龚超"));
    device.setProjectId(UUID.randomUUID().toString()); device.setOrganizationId(device.getProjectId());
    device.setName("201水表"); device.setMeterType("water");
    doAnswer(call -> Mono.just(result(call.getArgument(0),false))).when(drafts).process(any(),eq(false));
    doAnswer(call -> Mono.just(result(call.getArgument(0),true))).when(drafts).process(any(),eq(true));
  }
  Result result(MeterBillEntity row,boolean posted) {
    return new Result(row.getDeviceId(),row.getDeviceName(),row.getProjectId(),"room-201","5G园区/1栋/201","李白","water",row.getMonth(),
      new BigDecimal("12"),new BigDecimal("38.00"),"水费","阶梯水费","fingerprint-"+row.getReceivableDate(),posted?"bill-"+row.getId():null,posted?"YS2026110001":null,
      new Detail(row.getReceivableDate(),row.getDueDate(),"阶梯水费",new BigDecimal("3.00"),Map.of("WATER_USAGE",new BigDecimal("12")),List.of(),posted?"杜甫":null,posted?System.currentTimeMillis():null));
  }
  MeterBillEntity prepare() { return drafts.prepare(device,YearMonth.of(2026,10)).block(WAIT); }
  FacilityMeterReviews.Decision decision(MeterBillEntity row,String comment) {
    return new FacilityMeterReviews.Decision(row.getId(),row.getVersion(),comment,null,null);
  }
  @Test void preparationPersistsSnapshotWithoutPostingAndRepeatDoesNotRefreshIt() {
    var row=prepare(); assertEquals("pending",row.getState()); assertEquals("2026-11-01",row.getReceivableDate());
    assertEquals("2026-11-30",row.getDueDate());
    var saved=bills.findById(row.getId()).block(WAIT);
    assertEquals(new BigDecimal("38.00"),saved.getResult().amount());
    assertEquals("李白",saved.getCustomerName()); assertEquals("阶梯水费",saved.getResult().detail().formula());
    assertEquals(row.getId(),prepare().getId());
    verify(drafts,times(1)).process(any(),eq(false)); verify(drafts,never()).process(any(),eq(true));
  }
  @Test void concurrentApprovalsClaimOnceAndPublishAnAuditedLinkedBill() {
    var row=prepare(); var input=decision(row,"已核对读数");
    var outcomes=Flux.merge(reviews.batch("review",List.of(input)),reviews.batch("review",List.of(input)))
      .flatMapIterable(rows -> rows).collectList().block(WAIT);
    assertEquals(1,outcomes.stream().filter(FacilityMeterReviews.Outcome::success).count());
    verify(drafts,times(1)).process(any(),eq(true));
    var saved=bills.findById(row.getId()).block(WAIT); assertEquals("approved",saved.getState());
    assertEquals("bill-"+row.getId(),saved.getResult().billId()); assertEquals("杜甫",saved.getReviewerName());
    assertEquals(1,processes.createQuery().where("targetId",row.getId()).and("action","review").count().block(WAIT));
    assertFalse(reviews.batch("review",List.of(decision(saved,null))).block(WAIT).get(0).success());
  }
  @Test void rejectionRequiresReasonAndRecalculationPublishesANewVersionNotABill() {
    var row=prepare(); assertFalse(reviews.batch("reject",List.of(decision(row,"  "))).block(WAIT).get(0).success());
    var rejected=reviews.act("reject",decision(row,"请核对月底读数")).block(WAIT);
    assertEquals("rejected",rejected.getState()); assertEquals("请核对月底读数",rejected.getComment());
    assertFalse(reviews.batch("review",List.of(decision(rejected,null))).block(WAIT).get(0).success());
    var next=reviews.act("recalculate",new FacilityMeterReviews.Decision(row.getId(),rejected.getVersion(),null,"2026-11-11","2026-11-30")).block(WAIT);
    assertEquals("pending",next.getState()); assertEquals("fingerprint-2026-11-11",next.getResult().fingerprint());
    verify(drafts,never()).process(any(),eq(true));
    assertFalse(reviews.batch("review",List.of(decision(row,null))).block(WAIT).get(0).success());
  }
  @Test void uncertainRemoteCommitCanOnlyRecoverSameBillAndNeverRecalculateOrReject() {
    var row=prepare(); var writes=new AtomicInteger();
    doAnswer(call -> { writes.incrementAndGet(); return Mono.error(new IllegalStateException("finance response timed out")); })
      .when(drafts).process(any(),eq(true));
    assertFalse(reviews.batch("review",List.of(decision(row,null))).block(WAIT).get(0).success());
    var failed=bills.findById(row.getId()).block(WAIT); assertEquals("failed",failed.getState()); assertNotNull(failed.getError());
    assertFalse(reviews.batch("recalculate",List.of(decision(failed,null))).block(WAIT).get(0).success());
    assertFalse(reviews.batch("reject",List.of(decision(failed,"取消"))).block(WAIT).get(0).success());
    doAnswer(call -> Mono.just(result(call.getArgument(0),true))).when(drafts).process(any(),eq(true));
    assertTrue(reviews.batch("recover",List.of(decision(failed,null))).block(WAIT).get(0).success());
    var saved=bills.findById(row.getId()).block(WAIT); assertEquals("approved",saved.getState()); assertNull(saved.getError());
    assertEquals("bill-"+row.getId(),saved.getResult().billId()); assertEquals(1,writes.get());
  }
  @Test void missingDeviceAssetWritePermissionAndMixedBatchDoNotSilentlySucceed() {
    var row=prepare(); when(scope.assertRecord(any(),any(),anyString(),eq(org.jetlinks.pro.assets.crud.CrudAssetPermission.save)))
      .thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    var outcomes=reviews.batch("review",List.of(decision(row,null))).block(WAIT);
    assertFalse(outcomes.get(0).success()); assertEquals("pending",bills.findById(row.getId()).block(WAIT).getState());
    verify(drafts,never()).process(any(),eq(true));
    assertThrows(RuntimeException.class,() -> reviews.batch("review",Collections.nCopies(101,decision(row,null))).block(WAIT));
  }
  @Test void changedFinancialFingerprintReturnsToExceptionForExplicitRecalculation() {
    var row=prepare();
    doReturn(Mono.error(new org.hswebframework.web.exception.BusinessException("error.property_finance_meter_preview_changed",409)))
      .when(drafts).process(any(),eq(true));
    assertFalse(reviews.batch("review",List.of(decision(row,null))).block(WAIT).get(0).success());
    var failed=bills.findById(row.getId()).block(WAIT); assertEquals("error",failed.getState());
    assertEquals("pending",reviews.act("recalculate",decision(failed,null)).block(WAIT).getState());
  }
  @Test void tenThousandMonthlySnapshotsRemainSearchableWithExactPagingAndBatchLimits() {
    var rows=new ArrayList<MeterBillEntity>();
    for(int i=0;i<10001;i++) {
      var row=FacilityScope.initialize(new MeterBillEntity(),new FacilityContext("meter-tenant","meter-app","owner","龚超"));
      row.setProjectId(device.getProjectId()); row.setDeviceId("meter-"+i); row.setMonth("2026-10");
      row.setState(i%2==0?"pending":"approved"); row.setDeviceName("5G园区水表"+i); row.setAmount(new BigDecimal("38.00")); rows.add(row);
    }
    Flux.fromIterable(rows).buffer(500).concatMap(batch -> bills.insertBatch(Mono.just(batch))).then().block(WAIT);
    var q=new org.hswebframework.web.api.crud.entity.QueryParamEntity(); q.setPageSize(100); q.setPageIndex(1);
    long start=System.nanoTime();
    var page=billing.reviews(device.getProjectId(),"pending",q).block(WAIT);
    assertEquals(5001,page.getTotal()); assertEquals(100,page.getData().size()); assertEquals(1,page.getPageIndex());
    assertEquals(100,page.getData().stream().map(MeterBillEntity::getId).distinct().count());
    q.toQuery().where("deviceName","5G园区水表10000");
    assertEquals(1,billing.reviews(device.getProjectId(),"all",q).block(WAIT).getTotal());
    System.out.println("Meter review 10001-row filter/page elapsed ms="+(System.nanoTime()-start)/1_000_000);
  }
  @Test void monthlyPreparationKeysetCoversLaterDevicePagesAndDoesNotPostOnRepeatedPolls() {
    var rule=FacilityScope.initialize(new MeterChargeEntity(),new FacilityContext("meter-tenant","meter-app","owner","龚超"));
    rule.setProjectId(device.getProjectId()); rule.setEnergyType("water");
    var month=YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1); rule.setEffectiveFrom(month.atDay(1).toString());
    charges.insert(rule).block(WAIT);
    var batch=new ArrayList<DeviceEntity>();
    for(int i=0;i<205;i++) {
      var d=FacilityScope.initialize(new DeviceEntity(),new FacilityContext("meter-tenant","meter-app","owner","龚超"));
      d.setProjectId(device.getProjectId()); d.setCloudDeviceId(d.getId()); d.setName("201水表"+i); d.setMeterType("water");
      d.setEnergyEnabled(true); d.setProjectConfirmed(true); d.setAllocationSince(month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()); batch.add(d);
    }
    devices.insertBatch(Mono.just(batch)).block(WAIT);
    months.poll().block(WAIT); months.poll().block(WAIT);
    assertEquals(205,bills.createQuery().where("projectId",device.getProjectId()).count().block(WAIT));
    verify(drafts,times(205)).process(any(),eq(false)); verify(drafts,never()).process(any(),eq(true));
  }
  @Import({FacilityPersistenceTest.Config.class,MeterBillStore.class,MeterChargeStore.class,FacilityMeterDrafts.class,
    FacilityMeterReviews.class,FacilityMeterBilling.class,FacilityMeterMonths.class})
  static class Config { @Bean FacilityExecution execution() { return mock(FacilityExecution.class); } }
}
