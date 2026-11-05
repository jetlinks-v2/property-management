package org.jetlinks.pro.smartpark.handover.service;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;

@SpringBootTest(classes=HandoverPersistenceFixture.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class HandoverStageWorkspaceTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){HandoverPersistenceFixture.database(r);}
 @Autowired HandoverConfigurationService configuration;
 @Autowired HandoverProcessService process;
 @Autowired HandoverProcessRepository processes;
 @Autowired HandoverStageLogRepository logs;
 @Autowired HandoverRecordRepository records;
 @Autowired HandoverItemRepository items;
 @Autowired HandoverProblemRepository problems;
 @Autowired HandoverDirectory directory;
 private final reactor.util.context.Context employee=access("tester",Set.of("project"));
 @BeforeEach void setup(){
  for(var repo:List.of(processes,logs,records,items,problems))repo.createDelete().where("tenantId","tenant").execute().block(WAIT);
  HandoverPersistenceFixture.directory(directory,true,List.of());
 }
 @Test void invalidMeetingRangeDoesNotStartStageOrInsertRecord(){
  var row=new HandoverRecordEntity();row.setProjectId("project");row.setStage("kickoff");row.setTitle("启动会");
  row.setProperties(Map.of("meetingLocation","会议室","meetingStartTime","200","meetingEndTime","100"));
  assertThrows(BusinessException.class,()->configuration.save(records,row).contextWrite(employee).block(WAIT));
  assertEquals(0,records.createQuery().count().block(WAIT));assertEquals(0,logs.createQuery().count().block(WAIT));
 }
 @Test void completedStageCannotConfirmItems(){
  process.action("project","kickoff","start",0).contextWrite(employee).block(WAIT);
  var active=process.action("project","physical","start",1).contextWrite(employee).block(WAIT);
  process.action("project","physical","complete",active.getVersion()).contextWrite(employee).block(WAIT);
  var row=item("late");items.insert(row).block(WAIT);
  assertThrows(BusinessException.class,()->configuration.confirm("late",0).contextWrite(employee).block(WAIT));
  assertFalse(items.findById("late").block(WAIT).getConfirmed());
 }

 @Test void batchPreflightRejectsOneStaleRowWithoutAnyConfirmationOrLog(){
  process.action("project","kickoff","start",0).contextWrite(employee).block(WAIT);
  var a=item("a");a.setSource("MATERIAL_TRANSFER");var b=item("b");b.setSource("MATERIAL_TRANSFER");
  items.insertBatch(reactor.core.publisher.Mono.just(List.of(a,b))).block(WAIT);
  var request=batch(0,4);
  assertThrows(BusinessException.class,()->configuration.confirmBatch(request).contextWrite(employee).block(WAIT));
  assertEquals(0,items.createQuery().where("confirmed",true).count().block(WAIT));
  assertEquals(0,logs.createQuery().where("action","confirm").count().block(WAIT));
  var valid=batch(0,0);assertEquals(2,configuration.confirmBatch(valid).contextWrite(employee).block(WAIT).size());
  assertEquals(2,items.createQuery().where("confirmed",true).count().block(WAIT));
  var q=new HandoverQuery();q.setProjectId("project");q.setStage("material");
  assertEquals(3,process.history(q).contextWrite(employee).block(WAIT).getTotal());
 }
 private org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest batch(int a,int b){
  var request=new org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest();request.setProjectId("project");request.setSource("MATERIAL_TRANSFER");
  var one=new org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest.Item();one.setId("a");one.setVersion(a);
  var two=new org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest.Item();two.setId("b");two.setVersion(b);request.setItems(List.of(one,two));return request;
 }
 private HandoverItemEntity item(String id){var row=new HandoverItemEntity();row.setId(id);row.setTenantId("tenant");row.setApplicationId("application");row.setProjectId("project");row.setVersion(0);row.setDeleted(false);row.setName(id);row.setSource("PHYSICAL_HANDOVER");row.setConfirmed(false);return row;}
}
