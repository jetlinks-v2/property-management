package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.MetricMapping;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.*;
import reactor.core.publisher.Mono;

/** 隔离 PostgreSQL 执行真实报表 SQL；scope 注入授权交集，验证历史读取不扩大仪表范围。 */
@SpringBootTest(classes = FacilityPersistenceTest.Config.class,
  webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FacilityEnergyReportPersistenceTest {
  static final Duration WAIT = Duration.ofSeconds(30);
  static final FacilityContext CONTEXT = new FacilityContext("energy-tenant", "energy-app", "reader", "抄表员");
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    FacilityPersistenceTest.database(registry);
  }
  @Autowired FacilityReports reports;
  @Autowired FacilityReadings manager;
  @Autowired ReadingStore readings;
  @Autowired MappingStore mappings;
  @Autowired FacilityScope scope;
  @SpyBean FacilityRecords records;
  MappingEntity visible;

  @BeforeEach void setup() {
    reset(scope);
    clearInvocations(records);
    when(scope.current()).thenReturn(Mono.just(CONTEXT));
    when(scope.assertRecord(any(), any(), anyString(), any())).thenReturn(Mono.empty());
    when(scope.query(any(), eq(FacilityAssetType.facilityMapping), eq("id"))).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      // 合约边界：只授予 visible 和 foreign IDs；租户、应用及逻辑删除仍由 scope 取交集。
      return Mono.just(FacilityScope.applicationQuery(query, CONTEXT).toNestQuery()
        .in("id", List.of("energy-visible", "energy-foreign-tenant", "energy-foreign-app", "energy-deleted", "energy-submeter"))
        .getParam());
    });
    for (String project : List.of("energy-report-project", "energy-report-other"))
      readings.createDelete().where("projectId", project).execute().block(WAIT);
    mappings.createDelete().where("projectId", "energy-report-project").execute().block(WAIT);
    visible = mapping("visible");
    mappings.save(visible).block(WAIT);
    mappings.save(mapping("hidden")).block(WAIT);
    var foreignTenant = mapping("foreign-tenant"); foreignTenant.setTenantId("other-tenant");
    mappings.save(foreignTenant).block(WAIT);
    var foreignApp = mapping("foreign-app"); foreignApp.setApplicationId("other-app");
    mappings.save(foreignApp).block(WAIT);
    var deleted = mapping("deleted"); deleted.setDeleted(true);
    mappings.save(deleted).block(WAIT);
    var submeter = mapping("submeter"); submeter.setMeterLevel("submeter");
    mappings.save(submeter).block(WAIT);
    for (long time : List.of(500L, 1000L, 2000L, 2500L))
      save(reading("visible-" + time, time, time == 1000 ? 10 : time == 2000 ? 12 : 999));
    var pending = reading("pending", 1500, 999); pending.setStatus("pending"); save(pending);
    var wrongProperty = reading("secondary", 1500, 999); wrongProperty.setPropertyId("secondary"); save(wrongProperty);
    var otherProject = reading("other-project", 1500, 999); otherProject.setProjectId("energy-report-other"); save(otherProject);
    var otherTenant = reading("other-tenant", 1500, 999); otherTenant.setTenantId("other-tenant"); save(otherTenant);
    var otherApp = reading("other-app", 1500, 999); otherApp.setApplicationId("other-app"); save(otherApp);
  }

  @Test void reportMatchesIndependentlyAuthorizedUsageAndExcludesOtherScopes() {
    var result = reports.energy("energy-report-project", 1000, 2000, "master").block(WAIT);
    assertEquals(1, result.size());
    assertEquals(visible.getDeviceId(), result.get(0).get("deviceId"));
    var usage = (EnergyMath.Usage) result.get(0).get("usage");
    assertEquals(0, BigDecimal.valueOf(2).compareTo(usage.value()));
    assertEquals(2, usage.accepted()); assertTrue(usage.complete()); assertEquals(0, usage.anomalies());
    verify(records, never()).read(any(), anyString(), any(), any());
    assertEquals(manager.usageFor(visible, "total", 1000, 2000).block(WAIT), usage);
    verify(scope).query(any(), eq(FacilityAssetType.facilityMapping), eq("id"));
  }

  @Test void missingReadingsRemainIncompleteAndOtherProjectsStayEmpty() {
    readings.createDelete().where("projectId", "energy-report-project").execute().block(WAIT);
    var result = reports.energy("energy-report-project", 1000, 2000, "master").block(WAIT);
    assertEquals(1, result.size());
    var usage = (EnergyMath.Usage) result.get(0).get("usage");
    assertEquals(BigDecimal.ZERO, usage.value()); assertFalse(usage.complete()); assertEquals(0, usage.accepted());
    assertTrue(reports.energy("another-project", 1000, 2000, "master").block(WAIT).isEmpty());
  }

  @Test void projectSelectionIntersectsAssetTenantAndApplicationScope() {
    var all = reports.energyProjects(List.of(), 1000, 2000, "master").block(WAIT);
    var selected = reports.energyProjects(List.of("energy-report-project", "ungranted-project"), 1000, 2000, "master").block(WAIT);
    assertEquals(all, selected);
    assertEquals(1, all.size());
    assertTrue(reports.energyProjects(List.of("ungranted-project"), 1000, 2000, "master").block(WAIT).isEmpty());
    assertEquals(List.of(visible.getDeviceId()), selected.stream().map(row -> row.get("deviceId")).toList());
  }

  MappingEntity mapping(String id) {
    var row = FacilityScope.initialize(new MappingEntity(), CONTEXT);
    row.setId("energy-" + id); row.setDeviceId("energy-meter-" + id); row.setProjectId("energy-report-project");
    row.setName("总电表"); row.setMeterLevel("master"); row.setEnergyType("electricity");
    row.setUsagePropertyId("total"); row.setStaleAfterSeconds(60L);
    var metric = new MetricMapping(); metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("kWh");
    row.setMetrics(List.of(metric));
    return row;
  }

  ReadingEntity reading(String id, long time, int value) {
    var row = FacilityScope.initialize(new ReadingEntity(), CONTEXT);
    row.setId("energy-reading-" + id); row.setDeviceId("energy-meter-visible"); row.setProjectId("energy-report-project");
    row.setPropertyId("total"); row.setMetricKind("counter"); row.setUnit("kWh"); row.setStatus("accepted");
    row.setValue(BigDecimal.valueOf(value)); row.setObservedTime(time); row.setMappingVersion(0L); row.setMeterEpoch(0L);
    return row;
  }

  void save(ReadingEntity row) { readings.save(row).block(WAIT); }
}
