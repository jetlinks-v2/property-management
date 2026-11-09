package org.jetlinks.pro.smartpark.handover.service;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import static org.mockito.Mockito.*;

@org.springframework.context.annotation.Import(org.jetlinks.pro.smartpark.handover.export.HandoverExportRows.class)
@SpringBootTest(classes=HandoverPersistenceFixture.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE)
class HandoverRegisterTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { HandoverPersistenceFixture.database(registry); }
 @Autowired HandoverConfigurationService service;
 @Autowired HandoverScope scope;
 @Autowired org.jetlinks.pro.smartpark.handover.export.HandoverExportRows exports;
 @Autowired HandoverDirectory directory;
 @Autowired HandoverLedgerRepository ledgers;
 @Autowired HandoverEquipmentRepository equipment;
 @Autowired HandoverResponsibilityRepository units;
 @Autowired HandoverCategoryRepository categories;
 @BeforeEach void setup() {
  ledgers.createDelete().where("tenantId","tenant").execute().block(WAIT);
  units.createDelete().where("tenantId","tenant").execute().block(WAIT);
  HandoverPersistenceFixture.directory(directory,true,List.of("external-a"));
  categories.createDelete().where("tenantId","tenant").execute().block(WAIT);
  var category = new HandoverCategoryEntity(); category.setProjectId("project"); category.setId("ledger-category"); category.setName("渗漏");
  scope.initialize(category, new HandoverContext("tenant", "application", "tester", "tester")); categories.insert(category).block(WAIT);
  var unit=new HandoverResponsibilityEntity();unit.setProjectId("project");unit.setOrganizationId("external-a");
  service.save(units,unit).contextWrite(access("tester",Set.of("project"))).block(WAIT);
 }
 @Test void ledgerNeedsDescriptionButNotArtificialTitle() {
  var ledger=new HandoverLedgerEntity();ledger.setProjectId("project");ledger.setDescription("车库顶面渗水");ledger.setLocation("大堂");ledger.setCategoryId("ledger-category");ledger.setOrganizationId("external-a");ledger.setEntryUserId("forged");ledger.setEntryUserName("冒用身份");
  var saved=service.save(ledgers,ledger).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals("tester",saved.getEntryUserId());
  saved.setEntryUserId("forged-again"); saved.setEntryUserName("冒用身份");
  saved=service.save(ledgers,saved).contextWrite(access("editor",Set.of("project"))).block(WAIT);
  assertEquals("tester", saved.getEntryUserId()); assertEquals("tester", saved.getEntryUserName());assertNotNull(saved.getEntryTime());assertNotNull(saved.getId());assertEquals("车库顶面渗水",saved.getDescription());
 }

 @Test void informationPagesUseAuthorizedProjectsBeforePagingAndKeepEmptyScopeEmpty() {
  categories.createDelete().where("tenantId", "tenant").execute().block(WAIT);
  int sequence = 0;
  for (String projectId : List.of("project", "other", "project")) {
   var category = new HandoverCategoryEntity(); category.setProjectId(projectId); category.setName("渗漏" + sequence++);
   service.save(categories, category).contextWrite(access("tester", Set.of(projectId))).block(WAIT);
  }
  var allowed = new PropertyOrganizationInfo(); allowed.setId("project"); allowed.setName("5G园区");
  when(directory.projects()).thenReturn(Flux.just(allowed));
  var query = new HandoverQuery(); query.setPageSize(1);
  var page = service.queryInformation(categories, query).contextWrite(access("tester", Set.of("project"))).block(WAIT);
  assertEquals(2, page.getTotal()); assertEquals(1, page.getData().size()); assertEquals("project", page.getData().get(0).getProjectId());
  String firstId = page.getData().get(0).getId();
  var unitPage = service.queryInformation(units, query).contextWrite(access("tester", Set.of("project"))).block(WAIT);
  assertEquals(1, unitPage.getTotal()); assertEquals("external-a", unitPage.getData().get(0).getOrganizationId());
  query.setPageIndex(1);
  page = service.queryInformation(categories, query).contextWrite(access("tester", Set.of("project"))).block(WAIT);
  assertEquals(2, page.getTotal()); assertEquals(1, page.getData().size());
  assertEquals("project", page.getData().get(0).getProjectId()); assertNotEquals(firstId, page.getData().get(0).getId());
  query.setPageIndex(0); when(directory.projects()).thenReturn(Flux.empty());
  assertEquals(0, service.queryInformation(categories, query).contextWrite(access("tester", Set.of())).block(WAIT).getTotal());
  assertEquals(0, service.queryInformation(units, query).contextWrite(access("tester", Set.of())).block(WAIT).getTotal());
  query.setProjectId("other");
  assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class,
    () -> service.queryInformation(categories, query).contextWrite(access("tester", Set.of("project"))).block(WAIT));
 }

 @Test void responsibilityOnlyNeedsProjectAndOrganizationAndRejectsDuplicates() {
  var row = new HandoverResponsibilityEntity(); row.setProjectId("project"); row.setOrganizationId("external-b");
  row.setName("客户端伪造名称");
  var saved = service.save(units, row).contextWrite(access("tester", Set.of("project"))).block(WAIT);
  assertEquals("external-b", saved.getName()); assertNotNull(saved.getId());
  var duplicate = new HandoverResponsibilityEntity(); duplicate.setProjectId("project"); duplicate.setOrganizationId("external-b");
  assertThrows(BusinessException.class, () -> service.save(units, duplicate).contextWrite(access("tester", Set.of("project"))).block(WAIT));
  var invalid = new HandoverResponsibilityEntity(); invalid.setProjectId("project"); invalid.setOrganizationId("foreign");
  assertThrows(BusinessException.class, () -> service.save(units, invalid).contextWrite(access("tester", Set.of("project"))).block(WAIT));
 }

 @Test void supplementalPhotosPersistRealActorAndRejectReplayOrUnauthorizedUnit() {
  var row=new HandoverLedgerEntity();row.setProjectId("project");row.setDescription("补图测试");row.setCategoryId("ledger-category");row.setLocation("大堂");row.setOrganizationId("external-a");
  row=service.save(ledgers,row).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  var input=new ActionRequest();input.setVersion(row.getVersion());var photo=new FileRef();photo.setFileId("file-1");input.setPhotos(List.of(photo));
  var updated=service.supplementLedger(row.getId(),input).contextWrite(access("photographer",Set.of("project"))).block(WAIT);
  assertEquals("photographer",updated.getSupplementUserId());assertNotNull(updated.getSupplementTime());
  assertEquals("file-1",updated.getFiles().get(0).getFileId());
  final String id=row.getId();
  assertThrows(BusinessException.class,()->service.supplementLedger(id,input).contextWrite(access("tester",Set.of("project"))).block(WAIT));
  assertEquals(1,ledgers.findById(id).block(WAIT).getFiles().size());
  var wrong=new HandoverLedgerEntity();wrong.setProjectId("project");wrong.setDescription("越权单位");wrong.setOrganizationId("foreign");
  assertThrows(BusinessException.class,()->service.save(ledgers,wrong).contextWrite(access("tester",Set.of("project"))).block(WAIT));
 }
 @Test void equipmentRoundTripPreservesTypedFieldsAndFilteredPaging() {
  equipment.createDelete().where("tenantId","tenant").execute().block(WAIT);
  var row=new HandoverEquipmentEntity();row.setProjectId("project");row.setName("消防泵");row.setCategoryName("消防设备");
  row.setQuantity(new java.math.BigDecimal("2.125"));row.setLocation("地下1层");row.setProductionDate(100L);row.setInstallationDate(200L);
  row.setControlArea("A组团");row.setSupplier("设备供应公司");row.setProperties(java.util.Map.of("extra", "原参数保留"));
  var stored=service.save(equipment,row).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  var q=new HandoverQuery();q.setProjectId("project");q.setLocation("地下");q.setModifyTimeFrom(0L);
  var page=service.query(equipment,q,"name").contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals(1,page.getTotal());assertEquals("消防设备",page.getData().get(0).getCategoryName());
  assertEquals(0,new java.math.BigDecimal("2.125").compareTo(page.getData().get(0).getQuantity()));
  assertEquals("原参数保留",page.getData().get(0).getProperties().get("extra"));
  q.setLocation("三层");assertEquals(0,service.query(equipment,q,"name").contextWrite(access("tester",Set.of("project"))).block(WAIT).getTotal());
 }

 @Test void ledgerPagesFilterAuthorizedProjectsTenantApplicationAndDeletionBeforePaging() {
  int i = 0;
  for (String project : List.of("project", "other", "project", "project", "project", "project")) {
   var row=new HandoverLedgerEntity();row.setId("ledger-scope-"+i);row.setProjectId(project);row.setTitle("标题不含关键词");row.setDescription("渗漏"+i);row.setCategoryId("ledger-category");
   scope.initialize(row,new HandoverContext(i==3?"foreign-tenant":"tenant",i==4?"foreign-app":"application","tester","tester"));
   if(i==5) row.setDeleted(true); ledgers.insert(row).block(WAIT);i++;
  }
  var allowed=new PropertyOrganizationInfo();allowed.setId("project");allowed.setName("5G园区");when(directory.projects()).thenReturn(Flux.just(allowed));
  var query=new HandoverQuery();query.setPageSize(1);query.setKeyword("渗漏");
  var page=service.queryLedger(query).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals(2,page.getTotal());assertEquals(1,page.getData().size());assertEquals("渗漏",page.getData().get(0).getCategoryName());
  String first=page.getData().get(0).getId();query.setPageIndex(1);
  page=service.queryLedger(query).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals(2,page.getTotal());assertNotEquals(first,page.getData().get(0).getId());assertEquals("project",page.getData().get(0).getProjectId());
  var export = new ExportRequest();export.setKind("ledger");export.setFilters(java.util.Map.of("keyword","渗漏"));exports.validate(export);
  var exported=exports.page(export,0).contextWrite(access("tester",Set.of("project"))).block(WAIT);assertEquals(2,exported.size());
  assertTrue(exported.stream().allMatch(row -> "project".equals(row.getProjectId())));
  assertEquals("5G园区",exports.values(exported.get(0)).contextWrite(access("tester",Set.of("project"))).block(WAIT).get("projectName"));
  assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class,()->exports.verify(export,List.of("ledger-scope-1")).contextWrite(access("tester",Set.of("project"))).block(WAIT));
  var equipmentExport=new ExportRequest();equipmentExport.setKind("equipment");assertThrows(BusinessException.class,()->exports.validate(equipmentExport));
  when(directory.projects()).thenReturn(Flux.empty());query.setPageIndex(0);
  assertEquals(0,service.queryLedger(query).contextWrite(access("tester",Set.of())).block(WAIT).getTotal());
  query.setProjectId("other");assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class,()->service.queryLedger(query).contextWrite(access("tester",Set.of("project"))).block(WAIT));
 }

}
