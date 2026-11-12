package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.MetricMapping;
import org.jetlinks.pro.property.facility.model.ReadingInput;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import reactor.core.publisher.Mono;

/** 真正执行 SQL 计数与分页，校验固定计量范围及批量设备名称，授权 owner 由 scope 契约覆盖。 */
@SpringBootTest(classes = FacilityPersistenceTest.Config.class,
  webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FacilityReadingPageTest {
  static final Duration WAIT = Duration.ofSeconds(30);
  static final FacilityContext CONTEXT = new FacilityContext("reading-tenant", "reading-app", "reader", "抄表员");
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    FacilityPersistenceTest.database(registry);
  }
  @Autowired FacilityReadings manager;
  @Autowired ReadingStore readings;
  @Autowired DeviceStore devices;
  @Autowired MappingStore mappings;
  @Autowired FacilityScope scope;

  @BeforeEach void setup() {
    reset(scope);
    when(scope.current()).thenReturn(Mono.just(CONTEXT));
    when(scope.assertRecord(any(), any(), anyString(), any())).thenReturn(Mono.empty());
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      String assetField = call.getArgument(2);
      return Mono.just(FacilityScope.applicationQuery(query, CONTEXT).toNestQuery()
        .in(assetField, List.of("reading-device-a", "reading-device-b", "reading-missing")).getParam());
    });
    readings.createDelete().where("projectId", "reading-project").execute().block(WAIT);
    mappings.createDelete().where("projectId", "reading-project").execute().block(WAIT);
    for (String id : List.of("a", "b")) {
      var device = FacilityScope.initialize(new DeviceEntity(), CONTEXT);
      device.setId("reading-device-" + id);
      device.setProjectId("reading-project");
      device.setName(id.equals("a") ? "201水表" : "201燃气表");
      devices.save(device).block(WAIT);
    }
    String[] states = {"pending", "accepted", "rejected", "invalid", "superseded", "accepted"};
    for (int i = 0; i < 6; i++) {
      var row = reading("meter-" + i, i % 2 == 0 ? "counter" : "increment", i);
      row.setDeviceId(List.of("reading-device-a", "reading-device-b", "reading-missing").get(i % 3));
      row.setEnergyType(List.of("water", "gas", "electricity").get(i % 3));
      row.setSource(i % 2 == 0 ? "manual" : "cloud");
      row.setStatus(states[i]);
      readings.save(row).block(WAIT);
    }
    for (String kind : List.of("state", "instant", "balance")) {
      readings.save(reading(kind, kind, 99)).block(WAIT);
    }
    var missingKind = reading("legacy", null, 99);
    readings.save(missingKind).block(WAIT);
    var foreignTenant = reading("tenant", "counter", 99);
    foreignTenant.setTenantId("other-tenant");
    readings.save(foreignTenant).block(WAIT);
    var foreignApplication = reading("application", "counter", 99);
    foreignApplication.setApplicationId("other-app");
    readings.save(foreignApplication).block(WAIT);
    var hiddenDevice = reading("hidden", "counter", 99);
    hiddenDevice.setDeviceId("hidden-device");
    readings.save(hiddenDevice).block(WAIT);
    var deleted = reading("deleted", "counter", 99);
    deleted.setDeleted(true);
    readings.save(deleted).block(WAIT);
  }

  ReadingEntity reading(String id, String kind, long time) {
    var row = FacilityScope.initialize(new ReadingEntity(), CONTEXT);
    row.setId("reading-page-" + id);
    row.setProjectId("reading-project"); row.setProjectName("5G园区");
    row.setDeviceId("reading-device-a"); row.setMetricKind(kind);
    row.setPropertyId("total"); row.setObservedTime(time);
    row.setValue(BigDecimal.valueOf(time)); row.setStatus("accepted"); row.setSource("cloud");
    return row;
  }

  @Test void meteringAndScopeFilterBeforeCountingAndPagingEvenWithUserOr() {
    var query = new QueryParamEntity();
    query.setPageSize(2);
    query.toQuery().where("projectId", "reading-project").or("status", "invalid")
      .orderByAsc("observedTime");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      query.setPageIndex(i);
      var page = manager.page(query).block(WAIT);
      assertEquals(6, page.getTotal()); assertEquals(2, page.getData().size());
      ids.addAll(page.getData().stream().map(ReadingEntity::getId).toList());
      for (var row : page.getData()) {
        assertTrue(Set.of("counter", "increment").contains(row.getMetricKind()));
        assertEquals("5G园区", row.getProjectName());
        if (row.getDeviceId().equals("reading-missing")) assertNull(row.getDeviceName());
        else assertEquals(row.getDeviceId().endsWith("a") ? "201水表" : "201燃气表", row.getDeviceName());
      }
    }
    assertEquals(List.of("reading-page-meter-0", "reading-page-meter-1", "reading-page-meter-2",
      "reading-page-meter-3", "reading-page-meter-4", "reading-page-meter-5"), ids);
    assertEquals(2, query.getTerms().size());
    query.setPageIndex(0);
    query.toNestQuery().and("id", "no-such-reading");
    var empty = manager.page(query).block(WAIT);
    assertEquals(0, empty.getTotal()); assertTrue(empty.getData().isEmpty());
    assertEquals(14, readings.createQuery().where("projectId", "reading-project").count().block(WAIT));
  }

  @Test void deviceEnergySourceStatusAndMetricFiltersPreserveManualReviewRecord() {
    var query = new QueryParamEntity().toQuery().where("deviceId", "reading-device-a")
      .and("energyType", "water").and("source", "manual")
      .and("status", "pending").and("metricKind", "counter").getParam();
    var page = manager.page(query).block(WAIT);
    assertEquals(1, page.getTotal());
    var row = page.getData().get(0);
    assertEquals("reading-page-meter-0", row.getId()); assertEquals("201水表", row.getDeviceName());
    assertEquals("pending", readings.findById(row.getId()).block(WAIT).getStatus());
    devices.createUpdate().set("name", "201厨房水表").where("id", row.getDeviceId()).execute().block(WAIT);
    assertEquals("201厨房水表", manager.page(query).block(WAIT).getData().get(0).getDeviceName());
    query.toQuery().and("metricKind", "state");
    assertEquals(0, manager.page(query).block(WAIT).getTotal());
  }

  @Test void listedManualReadingCanBeApprovedOrRejectedButNotSelfReviewedOrReviewedTwice() {
    var pending = manager.page(new QueryParamEntity().toQuery()
      .where("status", "pending").getParam()).block(WAIT).getData().get(0);
    assertThrows(org.hswebframework.web.exception.BusinessException.class,
      () -> manager.review(pending.getId(), pending.getVersion(), true).block(WAIT));
    var author = pending.getCreatorId();
    when(scope.current()).thenReturn(Mono.just(new FacilityContext(
      CONTEXT.tenantId(), CONTEXT.applicationId(), "reviewer", "审核员")));
    var approved = manager.review(pending.getId(), pending.getVersion(), true).block(WAIT);
    assertEquals("accepted", approved.getStatus()); assertEquals("reviewer", approved.getReviewerId());
    assertThrows(org.hswebframework.web.exception.BusinessException.class,
      () -> manager.review(pending.getId(), pending.getVersion(), true).block(WAIT));
    var rejected = reading("reject", "counter", 100);
    rejected.setCreatorId(author); rejected.setStatus("pending"); rejected.setSource("manual");
    readings.save(rejected).block(WAIT);
    assertEquals("rejected", manager.review(rejected.getId(), rejected.getVersion(), false).block(WAIT).getStatus());
  }

  @Test void manualEntryNormalizesRawReadingAndOnlyApprovedRecordsEnterSeries() {
    var mapping = FacilityScope.initialize(new MappingEntity(), CONTEXT);
    mapping.setDeviceId("reading-device-a"); mapping.setProjectId("reading-project");
    mapping.setEnergyType("water"); mapping.setEnabled(true); mapping.setStaleAfterSeconds(86400L);
    var metric = new MetricMapping();
    metric.setPropertyId("manualTotal"); metric.setKind("counter"); metric.setUnit("m³");
    metric.setMultiplier(new BigDecimal("2")); metric.setScale(2);
    mapping.setMetrics(List.of(metric));
    mappings.save(mapping).block(WAIT);
    var input = new ReadingInput();
    input.setPropertyId("manualTotal"); input.setValue(new BigDecimal("6.25"));
    input.setObservedTime(1000000L); input.setReason("历史读数补录");
    var pending = manager.manual("reading-device-a", input).block(WAIT);
    assertEquals("pending", pending.getStatus()); assertEquals("manual", pending.getSource());
    assertEquals(0, new BigDecimal("6.25").compareTo(pending.getRawValue()));
    assertEquals(0, new BigDecimal("12.50").compareTo(pending.getValue()));
    assertTrue(manager.series("reading-device-a", "manualTotal", 999999, 2000001).block(WAIT).isEmpty());
    when(scope.current()).thenReturn(Mono.just(new FacilityContext(
      CONTEXT.tenantId(), CONTEXT.applicationId(), "reviewer", "审核员")));
    manager.review(pending.getId(), pending.getVersion(), true).block(WAIT);
    assertEquals(List.of(pending.getId()), manager.series("reading-device-a", "manualTotal", 999999, 2000001)
      .block(WAIT).stream().map(ReadingEntity::getId).toList());
    when(scope.current()).thenReturn(Mono.just(CONTEXT));
    input.setObservedTime(2000000L); input.setValue(new BigDecimal("7.25"));
    var other = manager.manual("reading-device-a", input).block(WAIT);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext(
      CONTEXT.tenantId(), CONTEXT.applicationId(), "reviewer", "审核员")));
    manager.review(other.getId(), other.getVersion(), false).block(WAIT);
    assertEquals(1, manager.series("reading-device-a", "manualTotal", 999999, 2000001).block(WAIT).size());
  }

  ReadingInput resubmitInput() {
    var mapping = FacilityScope.initialize(new MappingEntity(), CONTEXT);
    mapping.setDeviceId("reading-device-a"); mapping.setProjectId("reading-project");
    mapping.setEnergyType("water"); mapping.setEnabled(true);
    var metric = new MetricMapping();
    metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("m³");
    metric.setMultiplier(BigDecimal.ONE); metric.setScale(2);
    mapping.setMetrics(List.of(metric)); mappings.save(mapping).block(WAIT);
    var input = new ReadingInput();
    input.setPropertyId("total"); input.setValue(new BigDecimal("42.50"));
    input.setObservedTime(1234567L); input.setReason("修正录入错误");
    return input;
  }

  @Test void myRecordsIntersectCreatorAndManualSourceBeforePagingEvenWithUserOr() {
    var another = reading("another-person", "counter", 123);
    another.setSource("manual"); another.setCreatorId("someone-else");
    readings.save(another).block(WAIT);
    var query = new QueryParamEntity().toQuery().where("creatorId", "someone-else")
      .or("projectId", "reading-project").orderByAsc("observedTime").getParam();
    query.setPageSize(2);
    var first = manager.mine(query).block(WAIT);
    assertEquals(3, first.getTotal()); assertEquals(2, first.getData().size());
    assertTrue(first.getData().stream().allMatch(row -> CONTEXT.userId().equals(row.getCreatorId()) && "manual".equals(row.getSource())));
    query.setPageIndex(1);
    assertEquals(List.of("reading-page-meter-4"), manager.mine(query).block(WAIT).getData().stream().map(ReadingEntity::getId).toList());
    assertEquals(2, query.getTerms().size());
  }

  @Test void rejectedResubmissionCreatesPendingCopyKeepsOriginalAndCanOnlyHappenOnce() {
    var input = resubmitInput();
    var old = reading("retry", "counter", 1000);
    old.setSource("manual"); old.setStatus("rejected"); old.setReason("旧原因"); old.setRawValue(BigDecimal.TEN);
    readings.save(old).block(WAIT);
    var outcomes = reactor.core.publisher.Flux.merge(
      manager.resubmit(old.getId(), 0, input).map(row -> 1).onErrorReturn(0),
      manager.resubmit(old.getId(), 0, input).map(row -> 1).onErrorReturn(0)
    ).collectList().block(WAIT);
    assertEquals(1, outcomes.stream().mapToInt(Integer::intValue).sum());
    var original = readings.findById(old.getId()).block(WAIT);
    assertEquals("resubmitted", original.getStatus()); assertEquals("旧原因", original.getReason());
    assertEquals(0, BigDecimal.TEN.compareTo(original.getRawValue())); assertEquals(1000L, original.getObservedTime());
    var copies = readings.createQuery().where("resubmitsId", old.getId()).fetch().collectList().block(WAIT);
    assertEquals(1, copies.size()); var fresh = copies.get(0);
    assertEquals("pending", fresh.getStatus()); assertEquals(CONTEXT.userId(), fresh.getCreatorId());
    assertEquals("修正录入错误", fresh.getReason()); assertEquals(1234567L, fresh.getObservedTime());
    assertEquals(0, new BigDecimal("42.50").compareTo(fresh.getRawValue()));
    assertThrows(org.hswebframework.web.exception.BusinessException.class,
      () -> manager.resubmit(old.getId(), original.getVersion(), input).block(WAIT));
    when(scope.current()).thenReturn(Mono.just(new FacilityContext(CONTEXT.tenantId(), CONTEXT.applicationId(), "reviewer", "审核员")));
    assertEquals("accepted", manager.review(fresh.getId(), fresh.getVersion(), true).block(WAIT).getStatus());
  }

  @Test void resubmitRejectsOthersWrongStatesAndStaleVersionsAndRollsBackOnInvalidInput() {
    var input = resubmitInput(); var old = reading("retry-denied", "counter", 1000);
    old.setSource("manual"); old.setStatus("rejected"); readings.save(old).block(WAIT);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext(CONTEXT.tenantId(), CONTEXT.applicationId(), "other", "他人")));
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    when(scope.current()).thenReturn(Mono.just(CONTEXT));
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 9, input).block(WAIT));
    for (String state : List.of("pending", "accepted", "superseded", "resubmitted")) {
      readings.createUpdate().set("status", state).where("id", old.getId()).execute().block(WAIT);
      assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    }
    readings.createUpdate().set("status", "rejected").set("source", "cloud").where("id", old.getId()).execute().block(WAIT);
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    readings.createUpdate().set("source", "manual").where("id", old.getId()).execute().block(WAIT);
    input.setPropertyId("another-metric");
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    input.setPropertyId("total"); input.setObservedTime(System.currentTimeMillis() + 60000);
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    var unchanged = readings.findById(old.getId()).block(WAIT);
    assertEquals("rejected", unchanged.getStatus()); assertEquals(0, unchanged.getVersion());
    assertEquals(0, readings.createQuery().where("resubmitsId", old.getId()).count().block(WAIT));
    input.setObservedTime(1000L);
    mappings.createUpdate().set("enabled", false).where("deviceId", old.getDeviceId()).execute().block(WAIT);
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    assertEquals("rejected", readings.findById(old.getId()).block(WAIT).getStatus());
    mappings.createDelete().where("deviceId", old.getDeviceId()).execute().block(WAIT);
    assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
    assertEquals("rejected", readings.findById(old.getId()).block(WAIT).getStatus());
    when(scope.assertRecord(any(), any(), anyString(), any())).thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    input.setObservedTime(1000L);
    assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class, () -> manager.resubmit(old.getId(), 0, input).block(WAIT));
  }
}
