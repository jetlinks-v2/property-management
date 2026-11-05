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

@SpringBootTest(classes=HandoverPersistenceFixture.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE)
class HandoverRegisterTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { HandoverPersistenceFixture.database(registry); }
 @Autowired HandoverConfigurationService service;
 @Autowired HandoverScope scope;
 @Autowired HandoverDirectory directory;
 @Autowired HandoverLedgerRepository ledgers;
 @Autowired HandoverResponsibilityRepository units;
 @BeforeEach void setup() {
  ledgers.createDelete().where("tenantId","tenant").execute().block(WAIT);
  units.createDelete().where("tenantId","tenant").execute().block(WAIT);
  HandoverPersistenceFixture.directory(directory,true,List.of("external-a"));
  var unit=new HandoverResponsibilityEntity();unit.setProjectId("project");unit.setOrganizationId("external-a");
  service.save(units,unit).contextWrite(access("tester",Set.of("project"))).block(WAIT);
 }
 @Test void ledgerNeedsDescriptionButNotArtificialTitle() {
  var ledger=new HandoverLedgerEntity();ledger.setProjectId("project");ledger.setDescription("车库顶面渗水");
  var saved=service.save(ledgers,ledger).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals("tester",saved.getEntryUserId());assertNotNull(saved.getEntryTime());assertNotNull(saved.getId());assertEquals("车库顶面渗水",saved.getDescription());
 }

 @Test void supplementalPhotosPersistRealActorAndRejectReplayOrUnauthorizedUnit() {
  var row=new HandoverLedgerEntity();row.setProjectId("project");row.setDescription("补图测试");row.setOrganizationId("external-a");
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
}
