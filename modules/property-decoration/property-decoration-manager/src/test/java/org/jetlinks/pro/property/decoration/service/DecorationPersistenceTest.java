package org.jetlinks.pro.property.decoration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.base.archive.PropertyOwnerHouse;
import org.jetlinks.pro.property.base.archive.PropertyHouseContext;
import org.jetlinks.pro.property.base.archive.PropertyHouseInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真 PostgreSQL、聚合锁、唯一索引和响应式事务。仅替换公共目录与外部文件服务。 */
@SpringBootTest(classes=DecorationPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class DecorationPersistenceTest {
    static final Duration WAIT=Duration.ofSeconds(20);
    static final GenericContainer<?> POSTGRES=new GenericContainer<>("postgres:16-alpine")
        .withEnv("POSTGRES_PASSWORD","test-password").withEnv("POSTGRES_DB","decoration_test").withExposedPorts(5432)
        .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        POSTGRES.start();registry.add("spring.r2dbc.url",()->"r2dbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/decoration_test");
        registry.add("spring.r2dbc.username",()->"postgres");registry.add("spring.r2dbc.password",()->"test-password");
        registry.add("easyorm.dialect",()->"postgres");registry.add("easyorm.default-schema",()->"public");
    }
    @Autowired DecorationApplicationService applications;
    @Autowired org.springframework.transaction.ReactiveTransactionManager transactions;
    @Autowired DecorationReviewListService reviewList;
    @Autowired DecorationOwnerHouseService ownerHouses;
    @Autowired DecorationAcceptanceListService acceptanceList;
    @Autowired DecorationInspectionListService inspectionList;
    @Autowired DecorationConfigurationService configurations;
    @Autowired DecorationReviewService reviews;
    @Autowired DecorationPermitService permits;
    @Autowired DecorationTaskService workflow;
    @Autowired DecorationInspectionGenerator inspectionGenerator;
    @Autowired DecorationAttachmentService attachments;
    @Autowired DecorationFinanceService finance;
    @Autowired DecorationFinanceReceiptRepository financeReceipts;
    @Autowired DecorationApplicationRepository rows;
    @Autowired DecorationConfigurationRepository configs;
    @Autowired DecorationHistoryRepository histories;
    @Autowired DecorationNotificationRepository notificationRows;
    @Autowired DecorationNotificationService notifications;
    @Autowired DecorationNotificationRecipients noticeRecipients;
    @Autowired org.jetlinks.pro.property.decoration.integration.DecorationInsideMailSender mailSender;
    @Autowired DecorationTaskRepository tasks;
    @Autowired DecorationAttachmentRepository files;
    @Autowired DecorationScope scope;
    @Autowired DecorationAssigneeService assignees;
    @Autowired DecorationRoleService roles;
    @Autowired DecorationOverviewService overview;
    @Autowired DecorationDashboardService dashboard;
    @Autowired DecorationSelfInspectionService selfInspections;
    @Autowired DecorationSelfInspectionRepository selfInspectionRows;
    @Autowired DecorationPermitDocumentService documents;
    @Autowired DecorationProjectSealGateway projectSeals;

    @BeforeEach void prepare(){
        notificationRows.deleteById(notificationRows.createQuery().fetch().map(DecorationNotificationEntity::getId)).block(WAIT);
        reset(mailSender, noticeRecipients);
        when(noticeRecipients.allowed(anyString(),any())).thenReturn(Mono.just(true));
        when(noticeRecipients.roleMembers(any())).thenReturn(Flux.just("reviewer"));
        when(mailSender.send(any())).thenReturn(Mono.empty());
        selfInspectionRows.deleteById(selfInspectionRows.createQuery().fetch().map(DecorationSelfInspectionEntity::getId)).block(WAIT);
        rows.deleteById(rows.createQuery().fetch().map(DecorationApplicationEntity::getId)).block(WAIT);
        configs.deleteById(configs.createQuery().fetch().map(DecorationConfigurationEntity::getId)).block(WAIT);
        histories.deleteById(histories.createQuery().fetch().map(DecorationHistoryEntity::getId)).block(WAIT);
        tasks.deleteById(tasks.createQuery().fetch().map(DecorationTaskEntity::getId)).block(WAIT);
        files.deleteById(files.createQuery().fetch().map(DecorationAttachmentEntity::getId)).block(WAIT);
        financeReceipts.deleteById(financeReceipts.createQuery().fetch().map(DecorationFinanceReceiptEntity::getId)).block(WAIT);
        reset(scope, assignees, roles, projectSeals);
        when(projectSeals.read(any())).thenReturn(Mono.error(DecorationRules.error("project_seal_unavailable",503)));
        when(scope.current()).thenAnswer(inv->Authentication.currentReactive().map(auth->new DecorationScope.Context("tenant","app",auth.getUser().getId(),auth.getUser().getName(),auth)));
        when(scope.projects()).thenReturn(Flux.just(project("project")));
        when(scope.project(anyString())).thenAnswer(inv->"project".equals(inv.getArgument(0))?Mono.just(project("project")):Mono.error(new AccessDenyException()));
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("admin","管理员","",List.of("project"))));
        when(scope.query(any(),any())).thenCallRealMethod();
        when(scope.query(any(),any(),anyList())).thenCallRealMethod();
        when(scope.household(anyString(),anyString(),anyString(),anyBoolean())).thenAnswer(inv->{
            String project=inv.getArgument(0),space=inv.getArgument(1),customer=inv.getArgument(2);boolean mine=inv.getArgument(3);
            return Authentication.currentReactive().flatMap(auth->"project".equals(project)&&"house".equals(space)&&"customer".equals(customer)&&(!mine||"owner".equals(auth.getUser().getId()))
                ?Mono.just(new PropertyHouseholdInfo("project","house","一栋101","residential","customer","owner","业主","13800000000","relationship","owner"))
                :Mono.error(DecorationRules.error("household_mismatch",403)));
        });
        for(String kind:List.of("agreement","materials","checks")){
            var input=config(kind);configurations.save(input).contextWrite(auth("admin")).block(WAIT);
        }
    }
    @Test void createIdempotencyAndHouseOccupancyCannotBeBypassedByManualEnd(){
        var input=create("same");var row=createSigned(input,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals(row.getId(),createSigned(input,false).contextWrite(auth("admin")).block(WAIT).getId());
        assertEquals(1,histories.createQuery().count().block(WAIT));
        input.setConstructionUnit("变更内容");assertCode(createSigned(input,false),"request_conflict","admin");
        assertCode(createSigned(create("other"),false),"house_occupied","admin");
        var action=action(row);action.setRemark("施工取消");
        assertCode(reviews.end(row.getId(),action),"refund_completion_required","admin");
        var unchanged=rows.findById(row.getId()).block(WAIT);
        assertNotNull(unchanged.getActiveHouseKey());assertNull(unchanged.getActualEndTime());
        assertEquals("reviewing",unchanged.getStatus());assertEquals(0,unchanged.getRefundStatus());
        assertEquals(1L,unchanged.getVersion());assertEquals(1,histories.createQuery().count().block(WAIT));
        assertCode(createSigned(create("new"),false),"house_occupied","admin");
    }
    @Test void progressNotificationsAreTransactionalScopedAndRetryable(){
        var input=create("notice-create");
        var row=createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,notificationRows.createQuery().count().block(WAIT));
        createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,notificationRows.createQuery().count().block(WAIT));
        assertEquals(1,notifications.mine(new QueryParamEntity()).contextWrite(auth("owner")).block(WAIT).getTotal());
        assertEquals(0,notifications.mine(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
        var foreign=new DecorationNotificationEntity();foreign.setId("foreign-notice");foreign.setDecorationId(row.getId());foreign.setTenantId("tenant");foreign.setApplicationId("foreign-app");foreign.setProjectId("project");foreign.setUserId("owner");foreign.setState("pending");foreign.setAttempts(0);foreign.setCreateTime(1L);foreign.setNextAttemptTime(Long.MAX_VALUE);notificationRows.insert(foreign).block(WAIT);
        var forbiddenProject=new DecorationNotificationEntity();forbiddenProject.setId("foreign-project-notice");forbiddenProject.setDecorationId(row.getId());forbiddenProject.setTenantId("tenant");forbiddenProject.setApplicationId("app");forbiddenProject.setProjectId("forbidden");forbiddenProject.setUserId("owner");forbiddenProject.setState("pending");forbiddenProject.setAttempts(0);forbiddenProject.setCreateTime(1L);forbiddenProject.setNextAttemptTime(Long.MAX_VALUE);notificationRows.insert(forbiddenProject).block(WAIT);
        assertEquals(1,notifications.mine(new QueryParamEntity()).contextWrite(auth("owner")).block(WAIT).getTotal());
        var request=new DecorationRequests.Action();request.setVersion(row.getVersion());request.setRequestId("notice-rollback");
        StepVerifier.create(applications.mutate(row.getId(),"test-review",request,false,(value,ctx)->{value.setStatus("pending_payment");return Mono.empty();})
            .flatMap(value->Mono.error(new IllegalStateException("rollback")))
            .as(org.springframework.transaction.reactive.TransactionalOperator.create(transactions)::transactional).contextWrite(auth("admin")))
            .expectError(IllegalStateException.class).verify(WAIT);
        assertEquals(1,notificationRows.createQuery().where("applicationId","app").and("projectId","project").count().block(WAIT));
        assertEquals("reviewing",rows.findById(row.getId()).block(WAIT).getStatus());
        request.setRequestId("notice-reviewed");
        row=applications.mutate(row.getId(),"review",request,false,(value,ctx)->{value.setStatus("pending_payment");return Mono.empty();}).contextWrite(auth("admin")).block(WAIT);
        var notice=notificationRows.createQuery().where("event","reviewed").fetchOne().block(WAIT);
        assertEquals("owner",notice.getUserId());assertEquals("pay",notice.getStep());assertTrue(notice.getDetailUrl().contains("decorationId="+row.getId()));
        assertEquals("pending",notice.getState());
        when(mailSender.send(any())).thenReturn(Mono.error(new IllegalStateException("remote unavailable")));
        notifications.poll().block(WAIT);
        notice=notificationRows.findById(notice.getId()).block(WAIT);assertEquals("pending",notice.getState());assertEquals(1,notice.getAttempts());assertTrue(notice.getNextAttemptTime()>System.currentTimeMillis());
        notificationRows.createUpdate().set("nextAttemptTime",0L).where("id",notice.getId()).execute().block(WAIT);
        when(mailSender.send(any())).thenReturn(Mono.empty());notifications.poll().block(WAIT);
        notice=notificationRows.findById(notice.getId()).block(WAIT);assertEquals("sent",notice.getState());assertNotNull(notice.getSentTime());assertEquals(2,notice.getAttempts());
    }
    @Test void employeeTaskAndRectificationNoticesAreScopedRetryableAndTransactional(){
        var row=ready();task(row,"notice-acceptance",1,"firstAcceptance","staff","pending");
        var acceptance=tasks.findById("notice-acceptance").block(WAIT);
        notifications.task(row,acceptance,"firstTaskAssigned").contextWrite(auth("admin")).block(WAIT);
        notifications.task(row,acceptance,"firstTaskAssigned").contextWrite(auth("admin")).block(WAIT);
        var assigned=notificationRows.createQuery().where("event","firstTaskAssigned").fetchOne().block(WAIT);
        assertEquals("staff",assigned.getUserId());assertEquals("staff",assigned.getRecipientSide());
        assertEquals("notice-acceptance",assigned.getTaskId());assertTrue(assigned.getDetailUrl().contains("side=staff"));
        assertTrue(assigned.getDetailUrl().contains("taskId=notice-acceptance"));
        assertEquals(1,notifications.mine(new QueryParamEntity(),true).contextWrite(auth("staff")).block(WAIT).getTotal());
        assertEquals(0,notifications.mine(new QueryParamEntity(),true).contextWrite(auth("other")).block(WAIT).getTotal());
        assertEquals(0,notifications.mine(new QueryParamEntity()).contextWrite(auth("staff")).block(WAIT).getTotal());
        notifications.poll().block(WAIT);verify(mailSender).send(argThat(notice->"firstTaskAssigned".equals(notice.getEvent())));

        task(row,"notice-rectification",0,"inspectionRecord","inspector","owner_rectification");
        var task=tasks.findById("notice-rectification").block(WAIT);task.getDetails().setReviewRoleId("review");
        task.getDetails().setAssignedById("admin");tasks.save(task).block(WAIT);
        notifications.task(row,task,"rectificationRequired").contextWrite(auth("admin")).block(WAIT);
        var correction=rectify(row,task.getId());correction.setRemark("已完成整改，请复核");
        row=workflow.submitRectification(row.getId(),correction,true).contextWrite(auth("owner")).block(WAIT);
        var intent=notificationRows.createQuery().where("event","rectificationReview").fetchOne().block(WAIT);
        assertEquals("",intent.getUserId());assertEquals("review",intent.getRoleId());assertEquals("admin",intent.getDispatcherId());
        when(noticeRecipients.roleMembers(any())).thenReturn(Flux.error(new IllegalStateException("directory offline")));
        notifications.poll().block(WAIT);
        assertEquals("waiting_review",tasks.findById(task.getId()).block(WAIT).getStatus());
        assertEquals("pending",notificationRows.findById(intent.getId()).block(WAIT).getState());
        assertEquals(0,notifications.mine(new QueryParamEntity(),true).contextWrite(auth("reviewer","review")).block(WAIT).getTotal());
        when(noticeRecipients.roleMembers(any())).thenReturn(Flux.just("reviewer","reviewer"));
        notificationRows.createUpdate().set("nextAttemptTime",0L).where("id",intent.getId()).execute().block(WAIT);
        notifications.poll().block(WAIT);notifications.poll().block(WAIT);
        assertEquals(1,notifications.mine(new QueryParamEntity(),true).contextWrite(auth("reviewer","review")).block(WAIT).getTotal());
        assertEquals(0,notifications.mine(new QueryParamEntity(),true).contextWrite(auth("reviewer")).block(WAIT).getTotal());
        verify(mailSender,times(1)).send(argThat(notice->"reviewer".equals(notice.getUserId())));
        var reject=rectify(row,task.getId());reject.setPassed(false);reject.setRemark("请补充照片");
        row=workflow.reviewRectification(row.getId(),reject).contextWrite(auth("reviewer","review")).block(WAIT);
        assertEquals(1,notificationRows.createQuery().where("event","rectificationRejected").and("userId","owner").count().block(WAIT));
        var resubmit=rectify(row,task.getId());resubmit.setRemark("已补充");
        row=workflow.submitRectification(row.getId(),resubmit,true).contextWrite(auth("owner")).block(WAIT);
        var pass=rectify(row,task.getId());pass.setPassed(true);
        row=workflow.reviewRectification(row.getId(),pass).contextWrite(auth("reviewer","review")).block(WAIT);
        assertEquals(1,notificationRows.createQuery().where("event","rectificationPassed").and("userId","owner").count().block(WAIT));
        // 已完成的任务不再向旧复核人补发待办；点击历史通知仍须经过正常任务读取边界。
        notifications.poll().block(WAIT);
        assertTrue(notificationRows.createQuery().where("event","rectificationReview").and("userId", "").fetch().collectList().block(WAIT)
            .stream().anyMatch(notice->"cancelled".equals(notice.getState())));
        when(scope.employee()).thenReturn(Mono.error(DecorationRules.error("employee_required",403)));
        assertCode(notifications.mine(new QueryParamEntity(),true),"employee_required","staff");
    }

    @Test void invalidContactsAreRejectedBeforeCreatingAnApplication(){
        var input=create("invalid-contact");input.setContactPhone("123");
        var invalidOwnerPhone=assertThrows(org.hswebframework.web.exception.BusinessException.class,()->applications.create(input,false));
        assertEquals("error.decoration.invalid_owner_phone",invalidOwnerPhone.getMessage());
        input.setContactPhone("010-12345678");input.setManagerPhone("+8613900000000");
        assertEquals("error.decoration.invalid_manager_phone",assertThrows(org.hswebframework.web.exception.BusinessException.class,()->applications.create(input,false)).getMessage());
        input.setManagerPhone("13900000000");input.setOwnerCertificate("bad");
        assertEquals("error.decoration.invalid_owner_identity",assertThrows(org.hswebframework.web.exception.BusinessException.class,()->applications.create(input,false)).getMessage());
        input.setOwnerCertificate("11010519491231002x");input.setManagerCertificate("");
        assertEquals("error.decoration.invalid_manager_identity",assertThrows(org.hswebframework.web.exception.BusinessException.class,()->applications.create(input,false)).getMessage());
        assertEquals(0,rows.createQuery().count().block(WAIT));
        input.setManagerCertificate("130503670401001");
        var row=createSigned(input,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals("010-12345678",row.getContactPhone());
        assertEquals("130503670401001",row.getDetails().getManagerCertificate());
    }
    @Test void actualEndTimeOnlyCorrectsRefundedWorkflowWithIdempotencyAndVersionControl(){
        var row=createSigned(create("actual-end"),false).contextWrite(auth("admin")).block(WAIT);
        var input=new DecorationRequests.ChangeActualEndTime();input.setRequestId("set-actual-end");input.setVersion(row.getVersion());
        long start=row.getStartTime(),planned=row.getCompletionTime();input.setActualEndTime(start+1000);
        assertCode(reviews.changeActualEndTime(row.getId(),input),"refund_completion_required","admin");
        assertNotNull(rows.findById(row.getId()).block(WAIT).getActiveHouseKey());
        assertNull(rows.findById(row.getId()).block(WAIT).getActualEndTime());
        // 隔离库中的已退费快照，验证时间纠正不能制造或修改财务回执。
        row.setRefundStatus(2);row.setStatus("finished");row.setActiveHouseKey(null);row.setActualEndTime(start+500);
        rows.save(row).block(WAIT);
        rows.createUpdate().setNull("activeHouseKey").where("id",row.getId()).execute().block(WAIT);
        input.setActualEndTime(start-1);
        assertCode(reviews.changeActualEndTime(row.getId(),input),"invalid_dates","admin");
        assertEquals(1L,rows.findById(row.getId()).block(WAIT).getVersion());
        input.setActualEndTime(start+1000);
        row=reviews.changeActualEndTime(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("finished",row.getStatus());assertEquals(start+1000,row.getActualEndTime());assertNull(row.getActiveHouseKey());
        assertEquals(planned,row.getCompletionTime());assertEquals(planned,row.getDetails().getCompletionTime());
        assertNull(row.getEndReason());assertEquals(0,row.getPaymentStatus());assertEquals(2,row.getRefundStatus());
        assertEquals(2L,reviews.changeActualEndTime(row.getId(),input).contextWrite(auth("admin")).block(WAIT).getVersion());
        assertEquals(2,histories.createQuery().count().block(WAIT));
        input.setActualEndTime(start+2000);assertCode(reviews.changeActualEndTime(row.getId(),input),"request_conflict","admin");
        input.setRequestId("stale");assertCode(reviews.changeActualEndTime(row.getId(),input),"version_conflict","admin");
        var next=createSigned(create("same-house-again"),false).contextWrite(auth("admin")).block(WAIT);
        input.setVersion(row.getVersion());input.setRequestId("correct-ended-time");
        var corrected=reviews.changeActualEndTime(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals(start+2000,corrected.getActualEndTime());assertEquals("finished",corrected.getStatus());
        assertNotNull(rows.findById(next.getId()).block(WAIT).getActiveHouseKey());
        assertEquals(0,financeReceipts.createQuery().count().block(WAIT));
    }
    @Test void actualEndTimeCorrectionPreservesEndReasonAndFinancialSettlementWithinScope(){
        var row=ready();
        // 仅隔离库模拟已核验的结算快照；时间更正不得变更财务事实。
        row.setStatus("finished");row.setActiveHouseKey(null);row.setActualEndTime(System.currentTimeMillis());
        row.setRefundStatus(2);row.setRefundReference("settled-refund");row.setRefundedAmount(new BigDecimal("1000"));row.setRefundTime(row.getActualEndTime());
        rows.save(row).block(WAIT);
        rows.createUpdate().setNull("activeHouseKey").where("id",row.getId()).execute().block(WAIT);
        var end=action(row);end.setRemark("施工取消");
        row=reviews.end(row.getId(),end).contextWrite(auth("admin")).block(WAIT);
        rows.save(row).block(WAIT);
        var input=new DecorationRequests.ChangeActualEndTime();input.setRequestId("correct-settled");input.setVersion(row.getVersion());input.setActualEndTime(row.getActualEndTime()+1000);
        var corrected=reviews.changeActualEndTime(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("施工取消",corrected.getEndReason());assertEquals(2,corrected.getPaymentStatus());assertEquals(2,corrected.getRefundStatus());
        assertEquals("settled-refund",corrected.getRefundReference());assertEquals(row.getRefundTime(),corrected.getRefundTime());
        assertEquals(0,new BigDecimal("1000").compareTo(corrected.getRefundedAmount()));
        assertEquals(row.getCompletionTime(),corrected.getCompletionTime());assertNull(corrected.getActiveHouseKey());
        for(String field:List.of("application","tenant","project")){
            var foreign=cloneRow(corrected);foreign.setId("actual-end-foreign-"+field);
            if(field.equals("application"))foreign.setApplicationId("other-app");
            if(field.equals("tenant"))foreign.setTenantId("other-tenant");
            if(field.equals("project"))foreign.setProjectId("denied");
            rows.insert(foreign).block(WAIT);
            input.setRequestId("foreign-"+field);input.setVersion(foreign.getVersion());
            assertCode(reviews.changeActualEndTime(foreign.getId(),input),"not_found","admin");
            assertEquals(foreign.getActualEndTime(),rows.findById(foreign.getId()).block(WAIT).getActualEndTime());
        }
        assertEquals(0,financeReceipts.createQuery().count().block(WAIT));
    }
    @Test void actualEndTimeIsRequiredAndPermitContactIsSnapshottedFromPublicEmployee(){
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var input=new DecorationRequests.ChangeActualEndTime();input.setRequestId("valid");input.setVersion(1L);
            assertTrue(factory.getValidator().validate(input).stream().anyMatch(v->v.getPropertyPath().toString().equals("actualEndTime")));
            input.setActualEndTime(0L);assertFalse(factory.getValidator().validate(input).isEmpty());
            input.setActualEndTime(1L);assertTrue(factory.getValidator().validate(input).isEmpty());
        }
        var row=ready();rows.createUpdate().setNull("permit").where("id",row.getId()).execute().block(WAIT);
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("admin","管理员","13900000001",List.of("project"))));
        var input=new DecorationRequests.IssuePermit();input.setRequestId("issue");input.setVersion(row.getVersion());
        input.setSafetyPerson("安全负责人");input.setSafetyPhone("13900000002");input.setContent("装修");
        row=permits.issue(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("13900000001",row.getPermit().getApproverPhone());
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("admin","管理员","changed",List.of("project"))));
        assertEquals("13900000001",applications.read(row.getId(),false).contextWrite(auth("admin")).block(WAIT).getPermit().getApproverPhone());
    }
    @Test void inspectionAssignmentAndReviewRoleChangesRejectUntrustedRolesAndKeepNames(){
        var row=ready();
        when(roles.require("audit")).thenReturn(Mono.just(new DecorationRoleService.Role("audit","审核角色")));
        when(roles.require("review")).thenReturn(Mono.just(new DecorationRoleService.Role("review","复核角色")));
        when(roles.require("denied")).thenReturn(Mono.error(DecorationRules.error("invalid_assignees",403)));
        when(assignees.eligible(eq("inspector"),eq("project"),any())).thenReturn(Mono.just(new PropertyEmployeeInfo("inspector","巡检人","",List.of("project"))));
        var input=new DecorationRequests.Inspection();input.setRequestId("assign");input.setVersion(row.getVersion());input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("inspector-role",null,1)));
        when(roles.require("inspector-role")).thenReturn(Mono.just(new DecorationRoleService.Role("inspector-role","巡检角色")));
        input.setStartTime(System.currentTimeMillis()-10000);input.setEndTime(System.currentTimeMillis()+100000);
        input.setReviewRoleId("denied");
        assertCode(workflow.assignInspection(row.getId(),input),"invalid_assignees","admin");
        assertEquals(0,tasks.createQuery().count().block(WAIT));assertEquals(row.getVersion(),rows.findById(row.getId()).block(WAIT).getVersion());
        input.setReviewRoleId("review");row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        var task=tasks.createQuery().fetchOne().block(WAIT);assertNull(task.getDetails().getAuditRoleId());assertEquals("复核角色",task.getDetails().getReviewRoleName());
        task(row,"role-review",0,"inspectionRecord","inspector","waiting_review");
        var record=tasks.findById("role-review").block(WAIT);record.setDetails(task.getDetails());tasks.save(record).block(WAIT);task=record;
        var change=rectify(row,task.getId());change.setReviewRoleId("denied");
        assertCode(workflow.reassignRole(row.getId(),change),"invalid_assignees","admin");
        assertEquals("review",tasks.findById(task.getId()).block(WAIT).getDetails().getReviewRoleId());
        change.setReviewRoleId("audit");workflow.reassignRole(row.getId(),change).contextWrite(auth("admin")).block(WAIT);
        assertEquals("审核角色",tasks.findById(task.getId()).block(WAIT).getDetails().getReviewRoleName());
    }
    @Test void inspectionCadenceUsesEveryRoleAndGeneratesIndependentDatesIdempotently(){
        var row=ready(); long start=java.time.Instant.parse("2031-01-01T02:00:00Z").toEpochMilli();
        row=scheduledInspection(row,start,start+10*86400000L);
        assertEquals(0,tasks.createQuery().count().block(WAIT));
        assertEquals(DecorationInspectionGenerator.dayStart(start),row.getNextInspectionTime());
        long now=start+6*86400000L;
        var counts=Flux.merge(inspectionGenerator.generateBatch(now),inspectionGenerator.generateBatch(now)).collectList().block(WAIT);
        assertEquals(5,counts.stream().mapToInt(Integer::intValue).sum());
        var generated=tasks.createQuery().where("kind","daily").fetch().collectList().block(WAIT);
        assertEquals(5,generated.size());assertTrue(generated.stream().allMatch(t->"pending".equals(t.getStatus())));
        var byDay=generated.stream().collect(java.util.stream.Collectors.toMap(t->t.getDetails().getScheduledDate(),t->t));
        assertEquals(Set.of("2031-01-01","2031-01-03","2031-01-04","2031-01-05","2031-01-07"),byDay.keySet());
        assertEquals(2,byDay.get("2031-01-01").getDetails().getInspectionRoles().size());
        assertEquals("patrol-b",byDay.get("2031-01-04").getDetails().getInspectionRoles().get(0).roleId());
        assertEquals("review",byDay.get("2031-01-07").getDetails().getReviewRoleName());
        assertEquals("admin",byDay.get("2031-01-07").getCreatorId());
        assertEquals(start,byDay.get("2031-01-01").getDetails().getStartTime());
        assertEquals(DecorationInspectionGenerator.dayStart(start+86400000L)-1,byDay.get("2031-01-01").getDetails().getEndTime());
        assertEquals(0,inspectionGenerator.generateBatch(now).block(WAIT));
        assertEquals(row.getVersion(),rows.findById(row.getId()).block(WAIT).getVersion());

    }
    @Test void inspectionGenerationHasBoundedCatchupEndBoundaryAndStopsForEndedOrDeleted(){
        var row=ready(); long start=java.time.Instant.parse("2031-02-01T02:00:00Z").toEpochMilli();
        var input=inspection(row,start,start+65*86400000L);
        input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-a",null,1)));
        row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        row=freezeInspectionSchedule(row,start,start+65*86400000L);
        assertEquals(30,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        assertEquals(30,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        assertEquals(6,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        assertNull(rows.findById(row.getId()).block(WAIT).getNextInspectionTime());
        assertEquals(0,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        var last=tasks.createQuery().where("kind","daily").orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc("createTime")).fetch().collectList().block(WAIT)
            .stream().max(Comparator.comparing(t->t.getDetails().getScheduledDate())).orElseThrow();
        assertEquals(input.getEndTime(),last.getDetails().getEndTime());
        rows.createUpdate().set("nextInspectionTime",start).set("actualEndTime",start).where("id",row.getId()).execute().block(WAIT);
        assertEquals(0,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        rows.createUpdate().setNull("actualEndTime").set("deleted",true).where("id",row.getId()).execute().block(WAIT);
        assertEquals(0,inspectionGenerator.generateBatch(start+80*86400000L).block(WAIT));
        assertEquals(66,tasks.createQuery().count().block(WAIT));
    }
    @Test void sharedInspectionCanBeHandledOnceByAnyDueRoleButNotAnUnrelatedEmployee(){
        var row=ready();long start=System.currentTimeMillis()-1000;
        var input=inspection(row,start,start+86400000L);
        row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        var task=tasks.createQuery().where("kind","daily").fetchOne().block(WAIT);
        var feedback=feedback(row,task.getId());feedback.setPassed(true);
        assertCode(workflow.inspectionFeedback(row.getId(),feedback),"not_assignee","admin");
        var denied=workflow.inspectionFeedback(row.getId(),feedback).contextWrite(auth("stranger","other"));
        StepVerifier.create(denied).expectErrorMatches(e->e.getMessage().contains("not_assignee")).verify(WAIT);
        var both=Flux.merge(workflow.inspectionFeedback(row.getId(),feedback).contextWrite(auth("member-a","patrol-a")).map(r->true).onErrorReturn(false),
            workflow.inspectionFeedback(row.getId(),feedback).contextWrite(auth("member-b","patrol-b")).map(r->true).onErrorReturn(false)).collectList().block(WAIT);
        assertEquals(1,both.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,tasks.createQuery().where("kind","inspectionRecord").count().block(WAIT));
        var completed=tasks.findById(task.getId()).block(WAIT);assertEquals("completed",completed.getStatus());
        assertTrue(Set.of("member-a","member-b").contains(completed.getAssigneeId()));
        var latest=rows.findById(row.getId()).block(WAIT);var retry=feedback(latest,task.getId());retry.setPassed(true);
        StepVerifier.create(workflow.inspectionFeedback(row.getId(),retry).contextWrite(auth("member-b","patrol-b")))
            .expectErrorMatches(e->e.getMessage().contains("invalid_state")).verify(WAIT);
    }
    @Test void inspectionReassignmentPreservesTodaysOccurrenceSnapshot(){
        var row=ready();long now=System.currentTimeMillis();var input=inspection(row,now-1000,now+86400000L);
        row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        var original=tasks.createQuery().where("kind","daily").fetchOne().block(WAIT);
        input.setRequestId("reassign");input.setVersion(row.getVersion());input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-b","untrusted",1)));
        row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,tasks.createQuery().count().block(WAIT));
        assertEquals(2,tasks.findById(original.getId()).block(WAIT).getDetails().getInspectionRoles().size());
        assertEquals("patrol-b",row.getInspectionPlan().getInspectionRoles().get(0).roleName());
        assertEquals(1,inspectionGenerator.generateBatch(now+86400000L).block(WAIT));
        var future=tasks.createQuery().where("kind","daily").not("id",original.getId()).fetch().collectList().block(WAIT);
        assertEquals(1,future.size());assertEquals(1,future.get(0).getDetails().getInspectionRoles().size());
    }
    @Test void inspectionRolePeriodValidationAndDuplicateRoleAssignmentAreRejected(){
        var row=ready();var input=inspection(row,System.currentTimeMillis(),System.currentTimeMillis()+86400000L);
        input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-a",null,1),new DecorationModels.InspectionRole("patrol-a",null,2)));
        assertCode(workflow.assignInspection(row.getId(),input),"invalid_assignees","admin");
        assertEquals(0,tasks.createQuery().count().block(WAIT));assertNull(rows.findById(row.getId()).block(WAIT).getInspectionPlan());
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            input.setInspectionRoles(List.of());assertFalse(factory.getValidator().validate(input).isEmpty());
            input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-a",null,0)));assertFalse(factory.getValidator().validate(input).isEmpty());
            input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-a",null,2)));assertTrue(factory.getValidator().validate(input).isEmpty());
        }
    }
    private DecorationRequests.Inspection inspection(DecorationApplicationEntity row,long start,long end){
        for(String id:List.of("audit","review","patrol-a","patrol-b"))when(roles.require(id)).thenReturn(Mono.just(new DecorationRoleService.Role(id,id.equals("audit")?"审核角色":id)));
        var input=new DecorationRequests.Inspection();input.setRequestId(UUID.randomUUID().toString());input.setVersion(row.getVersion());input.setStartTime(start);input.setEndTime(end);
        input.setReviewRoleId("review");input.setInspectionRoles(List.of(new DecorationModels.InspectionRole("patrol-a",null,2),new DecorationModels.InspectionRole("patrol-b",null,3)));return input;
    }
    private DecorationApplicationEntity scheduledInspection(DecorationApplicationEntity row,long start,long end){
        row=workflow.assignInspection(row.getId(),inspection(row,start,end)).contextWrite(auth("admin")).block(WAIT);
        return freezeInspectionSchedule(row,start,end);
    }
    /** 生成器边界测试独立冻结时钟，不以客户端日期控制正式指派入口。 */
    private DecorationApplicationEntity freezeInspectionSchedule(DecorationApplicationEntity row,long start,long end){
        tasks.deleteById(tasks.createQuery().where("decorationId",row.getId()).fetch().map(DecorationTaskEntity::getId)).block(WAIT);
        row.getInspectionPlan().setStartTime(start);row.getInspectionPlan().setEndTime(end);
        row.setNextInspectionTime(DecorationInspectionGenerator.dayStart(start));rows.save(row).block(WAIT);return row;
    }
    @Test void inspectionAssignmentUsesServerDayAndCompletionTimeAndSynchronizesExtensions(){
        var row=ready();long plannedEnd=row.getCompletionTime();
        var input=inspection(row,1,2); // 旧客户端伪造时段不能改变计划。
        long before=System.currentTimeMillis();
        row=workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertTrue(row.getInspectionPlan().getStartTime()>=before);
        assertTrue(row.getInspectionPlan().getStartTime()<=System.currentTimeMillis());
        assertEquals(plannedEnd,row.getInspectionPlan().getEndTime());
        assertEquals(List.of(2,3),row.getInspectionPlan().getInspectionRoles().stream().map(DecorationModels.InspectionRole::intervalDays).toList());
        var today=tasks.createQuery().where("kind","daily").fetchOne().block(WAIT);
        assertEquals(DecorationInspectionGenerator.dayStart(before),DecorationInspectionGenerator.dayStart(today.getDetails().getStartTime()));
        long originalDeadline=today.getDetails().getEndTime();
        // 到期游标恢复场景：延期后无需重新选择角色或频率，历史任务不改写。
        rows.createUpdate().setNull("nextInspectionTime").where("id",row.getId()).execute().block(WAIT);
        var change=new DecorationRequests.ChangeEndTime();change.setRequestId("extend-inspection");change.setVersion(row.getVersion());change.setCompletionTime(plannedEnd+86400000L);
        row=reviews.changeEndTime(row.getId(),change).contextWrite(auth("admin")).block(WAIT);
        assertEquals(change.getCompletionTime(),row.getInspectionPlan().getEndTime());
        assertEquals(DecorationInspectionGenerator.dayStart(System.currentTimeMillis()),row.getNextInspectionTime());
        assertEquals(originalDeadline,tasks.findById(today.getId()).block(WAIT).getDetails().getEndTime());
        input=inspection(row,0,0);input.setStartTime(null);input.setEndTime(null);
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            assertTrue(factory.getValidator().validate(input).isEmpty());
        }
        assertEquals(change.getCompletionTime(),workflow.assignInspection(row.getId(),input).contextWrite(auth("admin")).block(WAIT).getInspectionPlan().getEndTime());
    }
    @Test void concurrentCreateAndConcurrentActionsNeverDuplicateHistory(){
        var results=Flux.merge(createSigned(create("one"),false).map(r->true).onErrorReturn(false),
            createSigned(create("two"),false).map(r->true).onErrorReturn(false)).collectList().contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals(1,rows.createQuery().count().block(WAIT));
        var row=rows.createQuery().fetchOne().block(WAIT);
        var a=new DecorationRequests.ChangeEndTime();a.setRequestId("same-action");a.setVersion(row.getVersion());a.setCompletionTime(row.getCompletionTime()+1000);
        var both=Flux.merge(reviews.changeEndTime(row.getId(),a),reviews.changeEndTime(row.getId(),a)).collectList().contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,both.size());assertEquals(2,histories.createQuery().count().block(WAIT));
    }
    @Test void concurrentSameCreateRequestResolvesToOneCommittedResult(){
        var input=create("same-concurrent");
        var results=Flux.merge(createSigned(input,false), createSigned(input,false)).collectList().contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,results.size());assertEquals(results.get(0).getId(),results.get(1).getId());
        assertEquals(1,rows.createQuery().count().block(WAIT));assertEquals(1,histories.createQuery().count().block(WAIT));
    }
    @Test void scopesConstrainOrFiltersDetailsStatisticsExportAndOwner(){
        var row=createSigned(create("one"),false).contextWrite(auth("admin")).block(WAIT);
        var foreign=cloneRow(row);foreign.setId("foreign");foreign.setApplicationId("another-app");foreign.setActiveHouseKey("foreign");rows.insert(foreign).block(WAIT);
        var denied=cloneRow(row);denied.setId("denied");denied.setProjectId("denied");denied.setActiveHouseKey("denied");rows.insert(denied).block(WAIT);
        var q=QueryParamEntity.newQuery().where("id","foreign").or("id",row.getId()).getParam();
        var page=applications.query(q,false).contextWrite(auth("admin")).block(WAIT);assertEquals(1,page.getTotal());assertEquals(row.getId(),page.getData().get(0).getId());
        assertCode(applications.read("foreign",false),"not_found","admin");assertCode(applications.read("denied",false),"not_found","admin");
        assertEquals(1,overview.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).summary().totalHouseCount());
        assertEquals(1,applications.export(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).size());
        assertCode(applications.read(row.getId(),true),"not_found","other-owner");
        assertNotNull(applications.read(row.getId(),true).contextWrite(auth("owner")).block(WAIT));
        assertCode(createSigned(create("forged"),true),"household_mismatch","other-owner");
    }
    @Test void reviewListOmitsSnapshotsAndKeepsReviewStatesScopeFiltersAndPaging() throws Exception {
        var row=createSigned(create("review-list"),false).contextWrite(auth("admin")).block(WAIT);
        var detail=row.getDetails();
        detail.getAgreement().setContent("private-signed-content".repeat(100000));
        detail.getSelfInspectionReview().setReviewStatus(1);
        var material=new DecorationModels.Material();var definition=new DecorationModels.MaterialDefinition();
        definition.setKind("material");definition.setRequired(false);material.setDefinition(definition);
        material.setFileIds(List.of("optional-upload"));material.setReviewStatus(0);
        var drawing=new DecorationModels.Material();var drawingDefinition=new DecorationModels.MaterialDefinition();
        drawingDefinition.setKind("drawing");drawing.setDefinition(drawingDefinition);drawing.setReviewStatus(2);
        detail.setMaterials(List.of(material,drawing));rows.updateById(row.getId(),row).block(WAIT);
        for(String boundary:List.of("application","tenant","project","deleted")) {
            var other=cloneRow(row);other.setId(boundary);other.setActiveHouseKey(boundary);
            switch(boundary) {
                case "application" -> other.setApplicationId("other");
                case "tenant" -> other.setTenantId("other");
                case "project" -> other.setProjectId("denied");
                case "deleted" -> other.setDeleted(true);
            }
            rows.insert(other).block(WAIT);
        }
        var q=QueryParamEntity.newQuery().where("id",row.getId()).or("id","application").or("id","tenant")
            .or("id","project").or("id","deleted").getParam();
        q.setIncludes(Set.of("details","permit","inspectionPlan"));q.setPageSize(1);
        var page=reviewList.query(q).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,page.getTotal());assertEquals(1,page.getData().size());
        var item=page.getData().get(0);assertEquals(row.getId(),item.getId());
        assertEquals(row.getNumber(),item.getNumber());assertEquals(row.getContactPhone(),item.getContactPhone());
        assertEquals("pending",item.getMaterialReview());assertEquals("rejected",item.getDrawingReview());
        var payload=new ObjectMapper().valueToTree(item);
        for(String field:List.of("details","permit","inspectionPlan","signedDocuments","materials"))assertFalse(payload.has(field),field);
        assertTrue(payload.toString().length()<2000);
        assertEquals(detail.getAgreement().getContent(),applications.read(row.getId(),false).contextWrite(auth("admin")).block(WAIT).getDetails().getAgreement().getContent());
        material.setReviewStatus(1);drawing.setReviewStatus(0);rows.updateById(row.getId(),row).block(WAIT);
        item=reviewList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals("approved",item.getMaterialReview());assertEquals("notRequired",item.getDrawingReview());
        var second=cloneRow(row);second.setId("second");second.setActiveHouseKey("second");second.setNumber("ZX-SECOND");
        second.getDetails().getSelfInspectionReview().setReviewStatus(2);rows.insert(second).block(WAIT);
        q=new QueryParamEntity();q.setPageSize(1);q.toQuery().orderByAsc("id");
        page=reviewList.query(q).contextWrite(auth("admin")).block(WAIT);assertEquals(2,page.getTotal());assertEquals(1,page.getData().size());
        q.setPageIndex(1);var next=reviewList.query(q).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,next.getTotal());assertNotEquals(page.getData().get(0).getId(),next.getData().get(0).getId());
        var onlySecond=QueryParamEntity.newQuery().where("number","ZX-SECOND").getParam();
        item=reviewList.query(onlySecond).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals("second",item.getId());assertEquals("rejected",item.getMaterialReview());
    }
    @Test void ownerHouseSummaryCountsHousesAndOmitsOtherOwnersApplications() {
        var first=createSigned(create("owner-house"),false).contextWrite(auth("admin")).block(WAIT);
        var second=cloneRow(first);second.setId("older");second.setActiveHouseKey(null);second.setRefundStatus(2);second.setCreateTime(first.getCreateTime()-1000);
        rows.insert(second).block(WAIT);
        for(String boundary:List.of("owner","tenant","application","project","deleted")) {
            var other=cloneRow(first);other.setId("foreign-"+boundary);other.setActiveHouseKey("foreign-"+boundary);
            switch(boundary) {
                case "owner" -> other.setOwnerUserId("someone-else");
                case "tenant" -> other.setTenantId("other");
                case "application" -> other.setApplicationId("other");
                case "project" -> other.setProjectId("denied");
                case "deleted" -> other.setDeleted(true);
            }
            rows.insert(other).block(WAIT);
        }
        var own=new PropertyHouseholdInfo("project","house","一栋101","residential","customer","owner","业主","13800000000","relationship","owner");
        var duplicate=new PropertyHouseholdInfo("project","house","一栋101","residential","customer","owner","业主","13800000000","relationship-duplicate","owner");
        var noApplication=new PropertyHouseholdInfo("project","empty-house","一栋102","residential","customer","owner","业主","13800000000","empty-relationship","owner");
        when(scope.households("project",true)).thenReturn(Flux.just(own,duplicate,noApplication));
        // 111个有权项目仅1个有本人房屋；候选不能越过项目或旧关系命令的空间权限。
        var projects=new ArrayList<PropertyOrganizationInfo>();projects.add(project("project"));
        for(int i=0;i<110;i++)projects.add(project("unrelated-"+i));
        when(scope.projects()).thenReturn(Flux.fromIterable(projects));
        when(scope.ownerHouses()).thenReturn(Mono.just(List.of(ownerHouse("project","house"),ownerHouse("project","house"),
            ownerHouse("project","empty-house"),ownerHouse("project","hidden-space"),ownerHouse("denied","house"))));
        clearInvocations(scope);
        var houses=ownerHouses.houses().contextWrite(auth("owner")).block(WAIT);
        assertEquals(2,houses.size());
        verify(scope,times(1)).projects();verify(scope,times(1)).ownerHouses();
        verify(scope,times(1)).households(anyString(),eq(true));verify(scope).households("project",true);
        assertEquals(2,houses.get(0).applicationCount());assertTrue(houses.get(0).processing());
        assertEquals(0,houses.get(1).applicationCount());assertFalse(houses.get(1).processing());
        var query=QueryParamEntity.newQuery().where("spaceId","house").getParam();query.setPageSize(1);query.setIncludes(Set.of("details","permit"));
        var page=ownerHouses.applications(query).contextWrite(auth("owner")).block(WAIT);
        assertEquals(2,page.getTotal());assertEquals(1,page.getData().size());assertEquals(first.getId(),page.getData().get(0).getId());
        var payload=new ObjectMapper().valueToTree(page.getData().get(0));
        assertFalse(payload.has("details"));assertFalse(payload.has("permit"));assertTrue(payload.toString().length()<1500);
        query.setPageIndex(1);assertEquals("older",ownerHouses.applications(query).contextWrite(auth("owner")).block(WAIT).getData().get(0).getId());
        assertEquals(0,ownerHouses.applications(new QueryParamEntity()).contextWrite(auth("unrelated")).block(WAIT).getTotal());
        rows.createUpdate().set("refundStatus",2).setNull("activeHouseKey").where("id",first.getId()).execute().block(WAIT);
        houses=ownerHouses.houses().contextWrite(auth("owner")).block(WAIT);
        assertEquals(2,houses.get(0).applicationCount());assertFalse(houses.get(0).processing());
        // 本人历史均结束，但同一房屋在其他账号或应用中仍被占用时，不能再次申请。
        for (String id : List.of("foreign-owner", "foreign-application")) {
            rows.createUpdate().set("activeHouseKey", first.getActiveHouseKey()).where("id", id).execute().block(WAIT);
            houses=ownerHouses.houses().contextWrite(auth("owner")).block(WAIT);
            assertEquals(2,houses.get(0).applicationCount());assertTrue(houses.get(0).processing());
            assertFalse(houses.get(1).processing());
            assertEquals(2,ownerHouses.applications(new QueryParamEntity()).contextWrite(auth("owner")).block(WAIT).getTotal());
            rows.createUpdate().setNull("activeHouseKey").where("id",id).execute().block(WAIT);
        }
        // 即使脏数据复用了占用键，也不能把其他租户占用误算为本人房屋装修中。
        rows.createUpdate().set("activeHouseKey",first.getActiveHouseKey()).where("id","foreign-tenant").execute().block(WAIT);
        houses=ownerHouses.houses().contextWrite(auth("owner")).block(WAIT);
        assertFalse(houses.get(0).processing());
        when(scope.households("project",true)).thenReturn(Flux.error(new IllegalStateException("directory offline")));
        StepVerifier.create(ownerHouses.houses().contextWrite(auth("owner"))).expectErrorMessage("directory offline").verify(WAIT);
        when(scope.ownerHouses()).thenReturn(Mono.just(List.of()));clearInvocations(scope);
        assertTrue(ownerHouses.houses().contextWrite(auth("owner")).block(WAIT).isEmpty());
        verify(scope,never()).households(anyString(),anyBoolean());
        when(scope.ownerHouses()).thenReturn(Mono.error(new IllegalStateException("owner directory offline")));
        StepVerifier.create(ownerHouses.houses().contextWrite(auth("owner"))).expectErrorMessage("owner directory offline").verify(WAIT);
    }
    private PropertyOwnerHouse ownerHouse(String projectId,String spaceId) {
        var house=new PropertyHouseInfo(spaceId,projectId,spaceId,spaceId,null,null,null,null,null,null,null,1L);
        return new PropertyOwnerHouse(projectId,new PropertyHouseContext(house,List.of()),List.of());
    }
    @Test void depositRequiresExplicitReviewAndFinanceBlocksWithoutAdapter(){
        var row=createSigned(create("one"),false).contextWrite(auth("admin")).block(WAIT);
        assertEquals("reviewing",row.getStatus());
        var review=new DecorationRequests.Review();review.setRequestId("deposit");review.setVersion(row.getVersion());review.setItemId("deposit");review.setResult(1);review.setAmount(new BigDecimal("1000"));
        row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);assertEquals("reviewing",row.getStatus());
        row=approveSelf(row);assertEquals("pending_payment",row.getStatus());
        var payment=new DecorationRequests.Finance();payment.setRequestId("pay");payment.setVersion(row.getVersion());payment.setReference("real-reference");
        assertCode(finance.process(row.getId(),payment,false,false),"finance_unavailable","admin");
        assertEquals(0,rows.findById(row.getId()).block(WAIT).getPaymentStatus());assertFalse(finance.capabilities().get("payment"));
        var permit=new DecorationRequests.IssuePermit();permit.setRequestId("permit");permit.setVersion(row.getVersion());
        assertCode(permits.issue(row.getId(),permit),"payment_required","admin");
        var stale=action(row);stale.setVersion(1L);stale.setRemark("取消");assertCode(reviews.end(row.getId(),stale),"version_conflict","admin");
    }
    @Test void permitDocumentAndPrintRequireValidAuthorizedSealWithoutWritingOnFailure() throws Exception {
        var row=ready();row.getPermit().setPrintTime(null);row.setStatus("pending_permit");rows.save(row).block(WAIT);
        long version=row.getVersion();long logs=histories.createQuery().count().block(WAIT);
        var input=new DecorationRequests.PrintPermit();input.setRequestId("print");input.setVersion(version);input.setSealDigest("a".repeat(64));
        assertCode(documents.prepare(row.getId()),"project_seal_unavailable","admin");
        assertCode(permits.print(row.getId(),input),"project_seal_unavailable","admin");
        assertNull(rows.findById(row.getId()).block(WAIT).getPermit().getPrintTime());
        assertEquals(version,rows.findById(row.getId()).block(WAIT).getVersion().longValue());assertEquals(logs,histories.createQuery().count().block(WAIT).longValue());
        var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",encoded);byte[] png=encoded.toByteArray();
        var request=new DecorationProjectSealGateway.Request("app","tenant","project");
        when(projectSeals.read(request)).thenReturn(Mono.just(new DecorationProjectSealGateway.Seal("app","tenant","project","image/png",png)));
        var document=documents.prepare(row.getId()).contextWrite(auth("admin")).block(WAIT);
        assertTrue(document.sealDataUrl().startsWith("data:image/png;base64,"));assertEquals(row.getId(),document.application().getId());
        assertCode(permits.print(row.getId(),input),"project_seal_changed","admin");
        assertNull(rows.findById(row.getId()).block(WAIT).getPermit().getPrintTime());
        input.setSealDigest(document.sealDigest());
        input.setSnapshotFileId("missing-permit-image");assertCode(permits.print(row.getId(),input),"invalid_attachment","admin");
        snapshot("first-print", row, "admin");input.setSnapshotFileId("first-print");var printed=permits.print(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("permit_issued",printed.getStatus());assertNotNull(printed.getPermit().getPrintTime());
        assertNull(printed.getPermit().getCollectedTime());assertTrue(DecorationRules.permitReceived(printed.getPermit()));
        assertEquals("print",printed.getPermit().getPrints().get(0).outputType());assertTrue(printed.getPermit().getCollections().isEmpty());
        assertEquals("first-print",printed.getPermit().getPrints().get(0).snapshotFileId());
        assertEquals("admin",printed.getPermit().getPrints().get(0).operatorId());assertEquals(version+1,printed.getVersion().longValue());
        assertEquals(printed.getVersion(),permits.print(row.getId(),input).contextWrite(auth("admin")).block(WAIT).getVersion());
        assertEquals(logs+1,histories.createQuery().count().block(WAIT).longValue());
        var foreign=cloneRow(printed);foreign.setId("foreign-document");foreign.setActiveHouseKey("foreign-document");foreign.setApplicationId("foreign");rows.insert(foreign).block(WAIT);
        clearInvocations(projectSeals);assertCode(documents.prepare(foreign.getId()),"not_found","admin");verifyNoInteractions(projectSeals);
    }
    @Test void printingAndDownloadingCompletePermitAndExtensionPreservesFrozenHistory() throws Exception {
        var row=ready();row.getPermit().setPrintTime(null);row.getPermit().setStartTime(System.currentTimeMillis()-86400000L);
        row.getPermit().setEndTime(System.currentTimeMillis()+86400000L);row.setStatus("pending_permit");rows.save(row).block(WAIT);
        var encoded=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",encoded);
        when(projectSeals.read(new DecorationProjectSealGateway.Request("app","tenant","project")))
            .thenReturn(Mono.just(new DecorationProjectSealGateway.Seal("app","tenant","project","image/png",encoded.toByteArray())));
        var doc=documents.prepare(row.getId()).contextWrite(auth("admin")).block(WAIT);
        snapshot("v1",row,"admin");var print=new DecorationRequests.PrintPermit();print.setRequestId("print-v1");print.setVersion(row.getVersion());print.setSealDigest(doc.sealDigest());print.setSnapshotFileId("v1");
        row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        var collect=new DecorationRequests.CollectPermit();collect.setRequestId("collect-v1");collect.setVersion(row.getVersion());collect.setCollectedBy("业主代领人");collect.setCollectedTime(System.currentTimeMillis());
        row=permits.collect(row.getId(),collect).contextWrite(auth("admin")).block(WAIT);
        assertEquals("permit_issued",row.getStatus());assertTrue(DecorationRules.permitReceived(row.getPermit()));
        assertEquals("业主代领人",row.getPermit().getCollectedBy());assertEquals("admin",row.getPermit().getCollectionOperatorId());
        assertEquals(row.getVersion(),permits.collect(row.getId(),collect).contextWrite(auth("admin")).block(WAIT).getVersion());
        clearInvocations(projectSeals);
        // 公共项目章变化/停机不能改变已经打印的图片，重复打印增加记录而不改变领取人。
        doc=documents.prepare(row.getId()).contextWrite(auth("admin")).block(WAIT);assertEquals("v1",doc.snapshotFileId());assertNull(doc.sealDataUrl());verifyNoInteractions(projectSeals);
        print.setRequestId("reprint-v1");print.setVersion(row.getVersion());row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,row.getPermit().getPrints().size());assertEquals("业主代领人",row.getPermit().getCollectedBy());verifyNoInteractions(projectSeals);
        long oldEnd=row.getPermit().getEndTime();var extend=new DecorationRequests.ExtendPermit();extend.setRequestId("extend-v2");extend.setVersion(row.getVersion());extend.setMonths(1);
        row=permits.extend(row.getId(),extend).contextWrite(auth("admin")).block(WAIT);
        assertEquals("pending_permit",row.getStatus());assertNull(row.getPermit().getCollectedTime());assertNull(row.getPermit().getPrintTime());assertFalse(DecorationRules.permitReceived(row.getPermit()));
        assertEquals(1,row.getPermit().getCollections().size());assertEquals(oldEnd,row.getPermit().getCollections().get(0).endTime());
        collect.setRequestId("too-early-v2");collect.setVersion(row.getVersion());assertCode(permits.collect(row.getId(),collect),"permit_snapshot_required","admin");
        doc=documents.prepare(row.getId()).contextWrite(auth("admin")).block(WAIT);assertNull(doc.snapshotFileId());assertNotNull(doc.sealDataUrl());
        snapshot("foreign-owner-image",row,"owner");print.setRequestId("print-v2");print.setVersion(row.getVersion());print.setSnapshotFileId("foreign-owner-image");assertCode(permits.print(row.getId(),print),"invalid_attachment","admin");
        snapshot("v2",row,"admin");print.setSnapshotFileId("v2");row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        assertEquals("permit_issued",row.getStatus());assertNull(row.getPermit().getCollectedTime());
        assertTrue(DecorationRules.permitReceived(row.getPermit()));
        print.setRequestId("download-v2");print.setVersion(row.getVersion());print.setOutputType("download");
        row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        assertEquals(row.getVersion(),permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT).getVersion());
        var saved=rows.findById(row.getId()).block(WAIT).getPermit();assertEquals(4,saved.getPrints().size());assertEquals(1,saved.getCollections().size());
        assertEquals("v1",saved.getCollections().get(0).snapshotFileId());assertEquals("v2",saved.getPrints().get(3).snapshotFileId());assertTrue(saved.getEndTime()>oldEnd);
        assertEquals("download",saved.getPrints().get(3).outputType());assertNotNull(saved.getDownloadTime());assertNull(saved.getCollectedTime());
        assertTrue(histories.createQuery().where("decorationId",row.getId()).fetch().collectList().block(WAIT).stream().anyMatch(log->"downloadPermit".equals(log.getAction())));
        // 下载完成也开放后续流程；重新输出不能将验收中状态倒退到备案阶段。
        row.setAcceptanceType(1);row.setStatus("first_acceptance_in_progress");rows.save(row).block(WAIT);
        print.setRequestId("download-during-acceptance");print.setVersion(row.getVersion());
        row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        assertEquals("first_acceptance_in_progress",row.getStatus());
        extend.setRequestId("extend-v3");extend.setVersion(row.getVersion());
        row.getPermit().setEndTime(System.currentTimeMillis());rows.save(row).block(WAIT);
        row=permits.extend(row.getId(),extend).contextWrite(auth("admin")).block(WAIT);
        assertNull(row.getPermit().getPrintTime());assertNull(row.getPermit().getDownloadTime());assertFalse(DecorationRules.permitReceived(row.getPermit()));
        doc=documents.prepare(row.getId()).contextWrite(auth("admin")).block(WAIT);
        snapshot("v3",row,"admin");print.setRequestId("download-only-v3");print.setVersion(row.getVersion());print.setSnapshotFileId("v3");print.setSealDigest(doc.sealDigest());
        row=permits.print(row.getId(),print).contextWrite(auth("admin")).block(WAIT);
        assertNotNull(row.getPermit().getDownloadTime());assertNull(row.getPermit().getPrintTime());assertTrue(DecorationRules.permitReceived(row.getPermit()));
    }
    private void snapshot(String id, DecorationApplicationEntity application, String creator) {
        var row=new DecorationAttachmentEntity();row.setId(id);row.setFileId(id);row.setApplicationId("app");row.setTenantId("tenant");row.setProjectId(application.getProjectId());
        row.setCreatorId(creator);row.setPurpose("permit");row.setDecorationId(application.getId());row.setContentType("image/png");row.setSize(1024L);row.setCreateTime(System.currentTimeMillis());files.insert(row).block(WAIT);
    }
    @Test void permitDocumentCannotBypassReviewOrPayment(){
        var row=createSigned(create("no-paid-document"),false).contextWrite(auth("admin")).block(WAIT);
        assertCode(documents.prepare(row.getId()),"payment_required","admin");verifyNoInteractions(projectSeals);
    }
    @Test void permitStatusAndPrintTimeFilterBeforePagingWithinTheAuthorizedScope(){
        var seed=ready();long now=System.currentTimeMillis();
        // 专用隔离库构造多页备案快照：未输出即使已延期且到期也仍为待办理。
        rows.deleteById(seed.getId()).block(WAIT);
        for(int i=0;i<25;i++){
            var row=cloneRow(seed);row.setId("permit-"+i);row.setActiveHouseKey(row.getId());
            var permit=row.getPermit();permit.setPrintTime(now-1000);permit.setEndTime(now+86400000);
            if(i==0)row.setPermit(null);
            if(i==1){permit.setPrintTime(null);permit.setEndTime(now-1000);permit.getExtensions().add(new DecorationModels.Extension());}
            if(i==2){permit.getExtensions().add(new DecorationModels.Extension());permit.setPrintTime(null);permit.setDownloadTime(now-1000);}
            if(i==3){permit.setEndTime(now-1000);permit.getExtensions().add(new DecorationModels.Extension());}
            if(i==4)permit.setEndTime(null);
            rows.insert(row).block(WAIT);
        }
        var foreign=cloneRow(seed);foreign.setId("foreign-permit");foreign.setActiveHouseKey(foreign.getId());foreign.setApplicationId("other-app");rows.insert(foreign).block(WAIT);
        for(var state:Map.of("pending",2,"extended",1,"expired",1,"issued",21).entrySet()){
            var query=QueryParamEntity.newQuery().accept("permit",DecorationPermitTermBuilder.TYPE,List.of(state.getKey())).getParam();
            assertEquals(state.getValue(),applications.query(query,false).contextWrite(auth("admin")).block(WAIT).getTotal());
        }
        var query=QueryParamEntity.newQuery().accept("permit",DecorationPermitTermBuilder.TYPE,List.of("issued")).getParam();query.setPageSize(20);
        var first=applications.query(query,false).contextWrite(auth("admin")).block(WAIT);assertEquals(21,first.getTotal());assertEquals(20,first.getData().size());
        query.setPageIndex(1);var last=applications.query(query,false).contextWrite(auth("admin")).block(WAIT);assertEquals(1,last.getData().size());
        assertFalse(first.getData().stream().anyMatch(row->row.getId().equals(last.getData().get(0).getId())));
        var notIssued=QueryParamEntity.newQuery().accept("permit",DecorationPermitTermBuilder.TYPE+"$not",List.of("issued")).getParam();
        assertEquals(4,applications.query(notIssued,false).contextWrite(auth("admin")).block(WAIT).getTotal());
        var printRange=QueryParamEntity.newQuery().accept("permit","json_value",org.hswebframework.ezorm.rdb.supports.json.JsonValueCondition.of("printTime","gte",now-2000))
            .accept("permit","json_value",org.hswebframework.ezorm.rdb.supports.json.JsonValueCondition.of("printTime","lte",now)).getParam();
        assertEquals(22,applications.query(printRange,false).contextWrite(auth("admin")).block(WAIT).getTotal());
        var orQuery=QueryParamEntity.newQuery().accept("permit",DecorationPermitTermBuilder.TYPE,List.of("expired"))
            .or("id","foreign-permit").getParam();
        assertEquals(1,applications.query(orQuery,false).contextWrite(auth("admin")).block(WAIT).getTotal());
        var invalid=QueryParamEntity.newQuery().accept("permit",DecorationPermitTermBuilder.TYPE,List.of("unknown")).getParam();
        assertCode(applications.query(invalid,false),"invalid_permit_state","admin");
    }
    @Test void snapshotsMaterialRejectionAndReplacementKeepHistory(){
        var material=new DecorationModels.MaterialDefinition();material.setId("drawing");material.setName("施工图");material.setKind("drawing");material.setSource("upload");material.setRequired(true);
        var config=config("materials");config.setVersion(1);config.getContent().setMaterials(new ArrayList<>(List.of(material)));configurations.save(config).contextWrite(auth("admin")).block(WAIT);
        file("original","admin","project",null);var input=create("one");var upload=new DecorationRequests.MaterialUpload();upload.setItemId("drawing");upload.setFileIds(List.of("original"));input.setMaterials(List.of(upload));
        var row=createSigned(input,false).contextWrite(auth("admin")).block(WAIT);
        file("marked","admin","project",row.getId());
        var review=new DecorationRequests.Review();review.setRequestId("reject");review.setVersion(row.getVersion());review.setItemId("drawing");review.setResult(2);review.setRemark("图纸不清晰");review.setAnnotatedFileId("marked");review.setAnnotationSourceFileId("original");review.setAnnotations(List.of(new DecorationModels.ImageMark(.1,.1,.5,.4,"补充尺寸")));row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
        file("new-file","admin","project",row.getId());upload.setFileIds(List.of("new-file"));var again=new DecorationRequests.Resubmit();again.setRequestId("resubmit");again.setVersion(row.getVersion());again.setMaterials(List.of(upload));
        row=reviews.resubmit(row.getId(),again,false).contextWrite(auth("admin")).block(WAIT);assertEquals(0,row.getDetails().getMaterials().get(0).getReviewStatus());
        assertEquals("original",applications.history(row.getId(),false).contextWrite(auth("admin")).blockFirst(WAIT).getSnapshot().getDetails().getMaterials().get(0).getFileIds().get(0));
        assertEquals(3,histories.createQuery().count().block(WAIT));
        var replacement=row.getDetails().getMaterials().get(0);assertNull(replacement.getAnnotatedFileId());assertNull(replacement.getAnnotationSourceFileId());assertTrue(replacement.getAnnotations().isEmpty());
        assertTrue(applications.history(row.getId(),false).contextWrite(auth("admin")).collectList().block(WAIT).stream()
            .anyMatch(h->"original".equals(h.getSnapshot().getDetails().getMaterials().get(0).getAnnotationSourceFileId())));
        review.setRequestId("approve-new-file");review.setVersion(row.getVersion());review.setResult(1);review.setRemark("");review.setAnnotatedFileId(null);review.setAnnotationSourceFileId(null);review.setAnnotations(List.of());
        assertEquals(1,reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT).getDetails().getMaterials().get(0).getReviewStatus());
    }
    @Test void attachmentsCannotCrossProjectAccountOrApplication(){
        file("private","other","project",null);file("foreign","admin","denied",null);file("ok","admin","project",null);
        var ctx=scope.current().contextWrite(auth("admin")).block(WAIT);
        assertCode(attachments.validate(List.of("private"),"project",null,ctx,false),"invalid_attachment","admin");
        assertCode(attachments.validate(List.of("foreign"),"project",null,ctx,false),"invalid_attachment","admin");
        attachments.validate(List.of("ok"),"project",null,ctx,false).block(WAIT);
        assertCode(attachments.download("foreign"),"invalid_attachment","admin");
    }
    @Test void acceptanceAggregatesOnlyCurrentRoundAndAssignee(){
        var row=ready();row.setAcceptanceType(1);row.setAcceptanceRound(2);row.setStatus("first_acceptance_in_progress");rows.save(row).block(WAIT);
        task(row,"old",1,"firstAcceptance","admin","failed");task(row,"one",2,"firstAcceptance","admin","pending");task(row,"two",2,"firstAcceptance","other","pending");
        var feedback=feedback(row,"one");row=workflow.acceptanceFeedback(row.getId(),feedback).contextWrite(auth("admin")).block(WAIT);assertEquals("first_acceptance_in_progress",row.getStatus());
        var wrong=feedback(row,"two");assertCode(workflow.acceptanceFeedback(row.getId(),wrong),"not_assignee","admin");
        row=workflow.acceptanceFeedback(row.getId(),wrong).contextWrite(auth("other")).block(WAIT);assertEquals("pending_second_acceptance",row.getStatus());
        assertCode(workflow.acceptanceFeedback(row.getId(),feedback(row,"old")),"invalid_state","admin");
    }
    @Test void aFailedFeedbackWaitsForAllAssigneesBeforeAllowingAnotherRound(){
        var row=ready();row.setAcceptanceType(1);row.setAcceptanceRound(1);row.setStatus("first_acceptance_in_progress");rows.save(row).block(WAIT);
        task(row,"first-person",1,"firstAcceptance","admin","pending");task(row,"second-person",1,"firstAcceptance","other","pending");
        var failure=feedback(row,"first-person");failure.setPassed(false);
        row=workflow.acceptanceFeedback(row.getId(),failure).contextWrite(auth("admin")).block(WAIT);
        assertEquals("first_acceptance_in_progress",row.getStatus());
        var retry=new DecorationRequests.AcceptanceRequest();retry.setRequestId("premature-retry");retry.setVersion(row.getVersion());retry.setType(1);retry.setAcceptanceDate(1900000000000L);
        assertCode(workflow.requestAcceptance(row.getId(),retry,true),"invalid_state","owner");
        row=workflow.acceptanceFeedback(row.getId(),feedback(row,"second-person")).contextWrite(auth("other")).block(WAIT);
        assertEquals("first_acceptance_failed",row.getStatus());assertEquals(1,row.getAcceptanceRound());
        assertEquals(0,tasks.createQuery().where("round",1).and("status","pending").count().block(WAIT));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void ownerAcceptanceRetryWaitsForStaffAssignmentAndPreservesFailedRound(int type){
        var row=ready();String prefix=type==1?"first":"second",kind=type==1?"firstAcceptance":"secondAcceptance";
        row.setSource("h5_owner");row.setAcceptanceType(type);row.setAcceptanceRound(1);row.setStatus(prefix+"_acceptance_failed");rows.save(row).block(WAIT);
        task(row,"failed-round",1,kind,"previous-inspector","failed");
        when(assignees.eligible(anyString(),anyString(),any())).thenReturn(Mono.error(new AccessDenyException()));
        var request=new DecorationRequests.AcceptanceRequest();request.setRequestId("owner-retry");request.setVersion(row.getVersion());request.setType(type);
        request.setAcceptanceDate(1900000090000L);request.setAssigneeIds(List.of("previous-inspector"));
        row=workflow.requestAcceptance(row.getId(),request,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(prefix+"_acceptance_waiting",row.getStatus());assertEquals(2,row.getAcceptanceRound());
        assertEquals("owner",row.getAcceptanceRequesterId());assertEquals("h5_owner",row.getAcceptanceSource());
        assertEquals(1,tasks.createQuery().count().block(WAIT));verifyNoInteractions(assignees);
        var duplicate=workflow.requestAcceptance(row.getId(),request,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(row.getVersion(),duplicate.getVersion());assertEquals(2,duplicate.getAcceptanceRound());
        var config=config("checks");config.setScene(kind);configurations.save(config).contextWrite(auth("admin")).block(WAIT);
        when(assignees.eligible(eq("new-inspector"),eq("project"),any(DecorationScope.Context.class)))
            .thenReturn(Mono.just(new PropertyEmployeeInfo("new-inspector","新验收人","",List.of("project"))));
        var assignment=new DecorationRequests.Acceptance();assignment.setRequestId("staff-reassign");assignment.setVersion(row.getVersion());assignment.setType(type);
        assignment.setAssigneeIds(List.of("new-inspector"));
        row=workflow.assignAcceptance(row.getId(),assignment).contextWrite(auth("admin")).block(WAIT);
        assertEquals(prefix+"_acceptance_in_progress",row.getStatus());assertEquals("failed",tasks.findById("failed-round").block(WAIT).getStatus());
        var next=tasks.createQuery().where("round",2).fetchOne().block(WAIT);
        assertNotNull(next);assertEquals("new-inspector",next.getAssigneeId());assertEquals("owner",next.getAcceptanceRequesterId());
        assertEquals(1900000090000L,next.getAcceptanceDate());assertEquals(2,tasks.createQuery().count().block(WAIT));
    }

    @Test void combinedAcceptanceIncludesEligibleWithoutInventingRequestsAndPreservesScope(){
        var row=ready();
        var first=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity(),true)
            .contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,first.getTotal());var candidate=first.getData().get(0);
        assertEquals("not_requested",candidate.getStatus());assertTrue(candidate.getRequestAllowed());
        assertNull(candidate.getRound());assertNull(candidate.getAcceptanceDate());assertNull(candidate.getAcceptanceRequestTime());
        assertNull(candidate.getCreateTime());assertNull(candidate.getAssigneeName());
        assertEquals(0,tasks.createQuery().count().block(WAIT));
        assertEquals(0,acceptanceList.query(DecorationAcceptanceListService.Kind.secondAcceptance,new QueryParamEntity(),true)
            .contextWrite(auth("admin")).block(WAIT).getTotal());
        for(String boundary:List.of("unpaid-permit","ended","application","tenant","project","deleted","owner")){
            var other=cloneRow(row);other.setId(boundary);other.setActiveHouseKey(boundary);other.setNumber("ZX-"+boundary);
            switch(boundary){
                case "unpaid-permit" -> other.setPermit(null);
                case "ended" -> other.setActualEndTime(1L);
                case "application" -> other.setApplicationId("other");
                case "tenant" -> other.setTenantId("other");
                case "project" -> other.setProjectId("denied");
                case "deleted" -> other.setDeleted(true);
                case "owner" -> other.setSource("h5_owner");
            }
            rows.insert(other).block(WAIT);
        }
        var query=QueryParamEntity.newQuery().where("status","not_requested").getParam();query.setPageSize(1);
        var page=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,query,true).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,page.getTotal());assertEquals(1,page.getData().size());
        var owner=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,QueryParamEntity.newQuery().where("id","owner").getParam(),true)
            .contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertFalse(owner.getRequestAllowed());
        row.setAcceptanceType(1);row.setAcceptanceRound(1);row.setStatus("pending_second_acceptance");rows.save(row).block(WAIT);
        task(row,"first-result",1,"firstAcceptance","admin","passed");
        var second=acceptanceList.query(DecorationAcceptanceListService.Kind.secondAcceptance,new QueryParamEntity(),true)
            .contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,second.getTotal());assertEquals("not_requested",second.getData().get(0).getStatus());
        assertTrue(second.getData().get(0).getRequestAllowed());assertNull(second.getData().get(0).getAcceptanceRequestTime());
        var previous=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,QueryParamEntity.newQuery().where("id",row.getId()).getParam(),true)
            .contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,previous.getTotal());assertEquals("passed",previous.getData().get(0).getStatus());assertFalse(previous.getData().get(0).getRequestAllowed());
    }
    @Test void acceptanceRequestMetadataSurvivesAssignmentAndSubsequentRounds(){
        var row=ready();
        var config=config("checks");config.setScene("firstAcceptance");configurations.save(config).contextWrite(auth("admin")).block(WAIT);
        when(assignees.eligible(eq("inspector"),eq("project"),any(DecorationScope.Context.class)))
            .thenReturn(Mono.just(new PropertyEmployeeInfo("inspector","验收人","",List.of("project"))));
        var request=new DecorationRequests.AcceptanceRequest();request.setRequestId("owner-request");request.setVersion(row.getVersion());request.setType(1);
        request.setAcceptanceDate(1900000000000L);request.setAssigneeIds(List.of("inspector"));
        row=workflow.requestAcceptance(row.getId(),request,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals("first_acceptance_waiting",row.getStatus());assertEquals("owner",row.getAcceptanceRequesterName());
        assertEquals("h5_owner",row.getAcceptanceSource());assertEquals(0,tasks.createQuery().count().block(WAIT));
        var record=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,record.getTotal());assertEquals("waiting_assignment",record.getData().get(0).getStatus());
        assertNull(record.getData().get(0).getAssigneeName());
        long submittedAt=row.getAcceptanceRequestTime();
        var duplicate=workflow.requestAcceptance(row.getId(),request,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(submittedAt,duplicate.getAcceptanceRequestTime());assertEquals(row.getVersion(),duplicate.getVersion());
        var assign=new DecorationRequests.Acceptance();assign.setRequestId("assign");assign.setVersion(row.getVersion());assign.setType(1);assign.setAssigneeIds(List.of("inspector"));
        row=workflow.assignAcceptance(row.getId(),assign).contextWrite(auth("admin")).block(WAIT);
        var first=tasks.createQuery().where("round",1).fetchOne().block(WAIT);
        assertEquals(1900000000000L,first.getAcceptanceDate());assertEquals(submittedAt,first.getAcceptanceRequestTime());
        assertEquals("owner",first.getAcceptanceRequesterId());assertEquals("owner",first.getAcceptanceRequesterName());assertEquals("admin",first.getCreatorName());
        assertEquals("13800000000",first.getContactPhone());assertEquals("施工单位",first.getConstructionUnit());
        assertEquals("负责人",first.getConstructionManager());assertEquals("13900000000",first.getManagerPhone());
        assertEquals("residential",first.getHouseType());assertEquals("first",first.getDecorationType());
        assertEquals(row.getCreateTime(),first.getApplicationTime());assertEquals(row.getStartTime(),first.getDecorationStartTime());
        assertEquals(row.getCompletionTime(),first.getCompletionTime());assertEquals("h5_owner",first.getAcceptanceSource());
        var filter=QueryParamEntity.newQuery().where("contactPhone","13800000000").and("acceptanceDate",1900000000000L).getParam();
        assertEquals(1,workflow.query(filter).contextWrite(auth("admin")).block(WAIT).getTotal());
        var feedback=feedback(row,first.getId());feedback.setPassed(false);
        row=workflow.acceptanceFeedback(row.getId(),feedback).contextWrite(auth("inspector")).block(WAIT);
        request.setRequestId("retry-round");request.setVersion(row.getVersion());request.setAcceptanceDate(1900000090000L);
        row=workflow.requestAcceptance(row.getId(),request,false).contextWrite(auth("admin")).block(WAIT);
        var second=tasks.createQuery().where("round",2).fetchOne().block(WAIT);
        assertEquals("first_acceptance_in_progress",row.getStatus());assertEquals("inspector",second.getAssigneeId());
        assertEquals(1900000090000L,second.getAcceptanceDate());assertEquals("admin",second.getAcceptanceRequesterName());assertEquals("pc_admin",second.getAcceptanceSource());
        var original=tasks.findById(first.getId()).block(WAIT);
        assertEquals(1900000000000L,original.getAcceptanceDate());assertEquals("owner",original.getAcceptanceRequesterName());assertEquals("failed",original.getStatus());
        record=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,record.getTotal());assertEquals("pending",record.getData().get(0).getStatus());assertEquals(2,record.getData().get(0).getRound());
        assertEquals("验收人",record.getData().get(0).getAssigneeName());assertEquals(submittedAt,record.getData().get(0).getCreateTime());
        row=workflow.acceptanceFeedback(row.getId(),feedback(row,second.getId())).contextWrite(auth("inspector")).block(WAIT);
        var secondRequest=new DecorationRequests.AcceptanceRequest();secondRequest.setRequestId("second-request");secondRequest.setVersion(row.getVersion());
        secondRequest.setType(2);secondRequest.setAcceptanceDate(1900000200000L);
        workflow.requestAcceptance(row.getId(),secondRequest,true).contextWrite(auth("owner")).block(WAIT);
        var firstRecord=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals("passed",firstRecord.getStatus());assertEquals("second_acceptance_waiting",firstRecord.getDecorationStatus());assertEquals(1900000090000L,firstRecord.getAcceptanceDate());
        assertEquals("admin",firstRecord.getAcceptanceRequesterName());assertEquals("pc_admin",firstRecord.getAcceptanceSource());
        var secondRecord=acceptanceList.query(DecorationAcceptanceListService.Kind.secondAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,secondRecord.getTotal());assertEquals("waiting_assignment",secondRecord.getData().get(0).getStatus());
        assertEquals(1900000200000L,secondRecord.getData().get(0).getAcceptanceDate());
        assertEquals(2,workflow.query(row.getId(),new QueryParamEntity(),false).contextWrite(auth("admin")).block(WAIT).getTotal());
    }
    @Test void acceptanceRecordsGroupLatestRoundBeforeFiltersAndPagingWithinAuthorizedApplications() throws Exception {
        var row=ready();
        assertEquals(0,acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
        row.setAcceptanceType(1);row.setAcceptanceRound(2);row.setAcceptanceRequestTime(1900000000000L);
        row.setStatus("first_acceptance_in_progress");rows.save(row).block(WAIT);
        task(row,"old-failure",1,"firstAcceptance","admin","failed");
        task(row,"current-failure",2,"firstAcceptance","admin","failed");
        task(row,"current-pending",2,"firstAcceptance","other","pending");
        var page=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,page.getTotal());var record=page.getData().get(0);
        assertEquals("pending",record.getStatus());assertEquals("admin、other",record.getAssigneeName());assertEquals(row.getId(),record.getDecorationId());
        assertFalse(new ObjectMapper().valueToTree(record).has("details"));
        for(String boundary:List.of("application","tenant","project","deleted","second")) {
            var other=cloneRow(row);other.setId(boundary);other.setActiveHouseKey(boundary);other.setNumber("ZX-"+boundary);
            switch(boundary) {
                case "application" -> other.setApplicationId("other");
                case "tenant" -> other.setTenantId("other");
                case "project" -> other.setProjectId("denied");
                case "deleted" -> other.setDeleted(true);
            }
            rows.insert(other).block(WAIT);
            task(other,boundary+"-task",2,"firstAcceptance","other","passed");
        }
        var query=QueryParamEntity.newQuery().where("id",row.getId()).or("id","application").or("id","tenant")
            .or("id","project").or("id","deleted").or("id","second").getParam();
        query.setPageSize(1);query.toQuery().orderByAsc("id");
        page=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,page.getTotal());assertEquals(1,page.getData().size());
        query.setPageIndex(1);var next=acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2,next.getTotal());assertNotEquals(page.getData().get(0).getId(),next.getData().get(0).getId());
        tasks.createUpdate().set("status","passed").where("id","current-pending").execute().block(WAIT);
        var failed=QueryParamEntity.newQuery().where("status","failed").getParam();
        assertEquals(row.getId(),acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,failed).contextWrite(auth("admin")).block(WAIT).getData().get(0).getId());
        tasks.createUpdate().set("status","passed").where("id","current-failure").execute().block(WAIT);
        assertEquals(0,acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,failed).contextWrite(auth("admin")).block(WAIT).getTotal());
        var person=QueryParamEntity.newQuery().where("assigneeName","like","%admin%").getParam();
        assertEquals(1,acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,person).contextWrite(auth("admin")).block(WAIT).getTotal());
        when(scope.projects()).thenReturn(Flux.empty());
        assertEquals(0,acceptanceList.query(DecorationAcceptanceListService.Kind.firstAcceptance,new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
    }
    @Test void businessListsShowCurrentDecorationStatusWithoutReplacingTaskOrSelfInspectionStatus(){
        var row=createSigned(create("list-current-status"),true).contextWrite(auth("owner")).block(WAIT);
        task(row,"status-task",1,"daily","admin","pending");
        var query=QueryParamEntity.newQuery().where("decorationId",row.getId()).getParam();
        var taskRow=workflow.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals("reviewing",taskRow.getDecorationStatus());assertEquals("pending",taskRow.getStatus());
        var self=selfInspections.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals("reviewing",self.getDecorationStatus());assertEquals(row.getDetails().getSelfInspectionStatus(),self.getStatus());
        rows.createUpdate().set("status","finished").where("id",row.getId()).execute().block(WAIT);
        assertEquals("finished",workflow.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0).getDecorationStatus());
        assertEquals("finished",selfInspections.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0).getDecorationStatus());
        // 历史任务本身保持原状态；关联申请不可见时不能泄漏其当前状态。
        rows.deleteById(row.getId()).block(WAIT);row.setTenantId("other");row.setStatus("finished");rows.insert(row).block(WAIT);
        assertNull(workflow.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0).getDecorationStatus());
        assertNull(selfInspections.query(query).contextWrite(auth("admin")).block(WAIT).getData().get(0).getDecorationStatus());
    }
    @Test void acceptanceDateIsRequiredOnlyWhenSubmittingAnApplication(){
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();var input=new DecorationRequests.AcceptanceRequest();
            input.setType(1);input.setRequestId("request");input.setVersion(1L);
            assertTrue(validator.validate(input).stream().anyMatch(v->"acceptanceDate".equals(v.getPropertyPath().toString())));
            input.setAcceptanceDate(1900000000000L);assertTrue(validator.validate(input).isEmpty());
            var assign=new DecorationRequests.Acceptance();assign.setType(1);assign.setRequestId("assign");assign.setVersion(1L);
            assertTrue(validator.validate(assign).isEmpty());
        }
    }
    @Test void personalTasksUseTrustedAssigneeOrRoleAndKeepPaginationAndScope() {
        var row=ready();
        task(row,"mine-acceptance",1,"firstAcceptance","staff","pending");
        task(row,"other-acceptance",1,"firstAcceptance","someone-else","pending");
        task(row,"role-daily",0,"daily",null,"pending");
        task(row,"other-role",0,"daily",null,"pending");
        task(row,"role-review",0,"inspectionRecord","someone-else","waiting_review");
        task(row,"my-history",0,"inspectionRecord","staff","passed");
        task(row,"other-history",0,"inspectionRecord","someone-else","passed");
        task(row,"my-review",0,"inspectionRecord","someone-else","rectified");
        var handled=tasks.findById("my-review").block(WAIT);
        handled.getDetails().getHandlingRecords().add(new DecorationModels.TaskHandlingRecord("reviewRectification","rectified","staff","复核员工",System.currentTimeMillis(),"已恢复",List.of(),"review","复核角色"));
        tasks.save(handled).block(WAIT);
        for(String id:List.of("role-daily","other-role")) {
            var task=tasks.findById(id).block(WAIT);
            task.getDetails().setInspectionRoles(List.of(new DecorationModels.InspectionRole("role-daily".equals(id)?"inspect":"other","角色",1)));
            tasks.save(task).block(WAIT);
        }
        var review=tasks.findById("role-review").block(WAIT);review.getDetails().setReviewRoleId("review");tasks.save(review).block(WAIT);
        var foreign=tasks.findById("mine-acceptance").block(WAIT);foreign.setId("foreign");foreign.setApplicationId("foreign");tasks.insert(foreign).block(WAIT);
        var all=workflow.mine(new QueryParamEntity()).contextWrite(auth("staff","inspect","review")).block(WAIT);
        assertEquals(Set.of("mine-acceptance","role-daily","role-review","my-history","my-review"),all.getData().stream().map(DecorationTaskEntity::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("mine-acceptance","role-daily"),all.getData().stream().filter(DecorationTaskEntity::isFeedbackAllowed).map(DecorationTaskEntity::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("role-review"),all.getData().stream().filter(DecorationTaskEntity::isReviewAllowed).map(DecorationTaskEntity::getId).toList());
        // 详情可读不等于本人可办；同一记录换读取人必须重新求值，不能把资格写进快照。
        var directory=workflow.query(new QueryParamEntity()).contextWrite(auth("staff","inspect","review")).block(WAIT);
        assertEquals(Set.of("mine-acceptance","role-daily"),directory.getData().stream().filter(DecorationTaskEntity::isFeedbackAllowed).map(DecorationTaskEntity::getId).collect(java.util.stream.Collectors.toSet()));
        var detail=workflow.query(row.getId(),new QueryParamEntity(),false).contextWrite(auth("someone-else")).block(WAIT);
        assertEquals(List.of("other-acceptance"),detail.getData().stream().filter(DecorationTaskEntity::isFeedbackAllowed).map(DecorationTaskEntity::getId).toList());
        assertTrue(detail.getData().stream().noneMatch(DecorationTaskEntity::isReviewAllowed));
        assertFalse(tasks.findById("mine-acceptance").block(WAIT).isFeedbackAllowed());
        var page=new QueryParamEntity();page.setPageSize(1);
        var first=workflow.mine(page).contextWrite(auth("staff","inspect","review")).block(WAIT);
        assertEquals(5,first.getTotal());assertEquals(1,first.getData().size());
        var attack=QueryParamEntity.newQuery().where("assigneeId","someone-else").or("id","foreign").getParam();
        var denied=workflow.mine(attack).contextWrite(auth("staff")).block(WAIT);
        // 输入条件可命中本人曾复核、但由别人巡检的记录，不能扩展到其他记录或应用。
        assertEquals(List.of("my-review"),denied.getData().stream().map(DecorationTaskEntity::getId).toList());
        assertEquals(0,workflow.mine(attack).contextWrite(auth("outsider")).block(WAIT).getTotal());
        when(scope.employee()).thenReturn(Mono.error(DecorationRules.error("employee_required",403)));
        assertCode(workflow.mine(new QueryParamEntity()),"employee_required","staff");
    }
    @Test void inspectionListKeepsOneRowFromScheduledTaskThroughRectification() {
        var row=ready();
        var daily=scheduledInspection(row,"scheduled","pending");
        daily.setSpaceName("一栋101");daily.setProjectName("项目");daily.setModifyTime(1000L);
        daily.getDetails().setScheduledDate("2026-11-12");
        daily.getDetails().setStartTime(System.currentTimeMillis()-10000);daily.getDetails().setEndTime(System.currentTimeMillis()+100000);
        daily.getDetails().setInspectionRoles(List.of(new DecorationModels.InspectionRole("inspect","巡检员",3)));
        daily.getDetails().setReviewRoleId("review");tasks.save(daily).block(WAIT);
        var pending=inspectionList.query(new QueryParamEntity()).contextWrite(auth("inspector","inspect")).block(WAIT);
        assertEquals(1,pending.getTotal());assertTrue(pending.getData().get(0).isFeedbackAllowed());
        assertEquals("pending",pending.getData().get(0).getInspectionResult());
        assertEquals("2026-11-12",pending.getData().get(0).getScheduledDate());
        assertFalse(inspectionList.query(new QueryParamEntity()).contextWrite(auth("other")).block(WAIT).getData().get(0).isFeedbackAllowed());
        var input=feedback(row,"scheduled");input.setPassed(false);input.setRemark("巡检发现违规");
        var violation=new DecorationModels.Violation();violation.setLocation("阳台");violation.setRectification("恢复原状");input.setViolations(List.of(violation));
        row=workflow.inspectionFeedback(row.getId(),input).contextWrite(auth("inspector","inspect")).block(WAIT);
        var record=tasks.createQuery().where("parentId","scheduled").fetchOne().block(WAIT);
        var result=inspectionList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,result.getTotal());assertEquals("scheduled",result.getData().get(0).getId());
        assertEquals("violation",result.getData().get(0).getInspectionResult());assertEquals("owner_rectification",result.getData().get(0).getStatus());
        assertEquals("inspector",result.getData().get(0).getAssigneeName());
        var correction=rectify(row,record.getId());correction.setRemark("已整改");
        row=workflow.submitRectification(row.getId(),correction,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals("waiting_review",inspectionList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getData().get(0).getStatus());
        var review=rectify(row,record.getId());review.setPassed(true);review.setRemark("复核通过");
        workflow.reviewRectification(row.getId(),review).contextWrite(auth("reviewer","review")).block(WAIT);
        result=inspectionList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,result.getTotal());assertEquals("rectified",result.getData().get(0).getStatus());
        assertEquals(tasks.findById(record.getId()).block(WAIT).getModifyTime(),result.getData().get(0).getModifyTime());
        assertEquals(2,tasks.createQuery().count().block(WAIT)); // 只合并投影，任务与反馈独立持久化。
    }
    @Test void inspectionListFiltersLatestFeedbackBeforePagingAndPreservesScope() {
        var row=ready();var daily=scheduledInspection(row,"scheduled","completed");scheduledInspection(row,"next-scheduled","pending");
        daily.setSpaceName("一栋101");daily.setModifyTime(1000L);tasks.save(daily).block(WAIT);
        for(var id:List.of("old-feedback","new-feedback","foreign-feedback")){
            var feedback=new DecorationTaskEntity();feedback.setId(id);feedback.setParentId("scheduled");feedback.setKind("inspectionRecord");
            feedback.setApplicationId("foreign-feedback".equals(id)?"other-app":row.getApplicationId());feedback.setTenantId(row.getTenantId());
            feedback.setProjectId(row.getProjectId());feedback.setDecorationId(row.getId());feedback.setAssigneeName("inspector");
            feedback.setStatus("old-feedback".equals(id)?"reissued":"foreign-feedback".equals(id)?"passed":"rectification_rejected");
            feedback.setCreateTime("old-feedback".equals(id)?2000L:3000L);feedback.setModifyTime("old-feedback".equals(id)?9000L:8000L);
            feedback.setVersion(1L);feedback.setRound(0);feedback.setDetails(new DecorationModels.TaskDetails());tasks.insert(feedback).block(WAIT);
        }
        // 关联ID相同的其他应用反馈不能污染本应用状态。
        var query=QueryParamEntity.newQuery().where("spaceName","一栋101").and("modifyTime","btw",List.of(8500L,9500L)).getParam();
        query.setPageSize(1);
        var page=inspectionList.query(query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,page.getTotal());assertEquals(1,page.getData().size());
        assertEquals("scheduled",page.getData().get(0).getId());assertEquals("rectification_rejected",page.getData().get(0).getStatus());
        assertEquals(2,inspectionList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
        assertEquals(0,inspectionList.query(QueryParamEntity.newQuery().where("projectId","other").getParam()).contextWrite(auth("admin")).block(WAIT).getTotal());
        row.setDeleted(true);rows.save(row).block(WAIT);
        assertEquals(0,inspectionList.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
    }
    @Test void failedInspectionDirectlyReachesOwnerAndKeepsRoleReview(){
        var row=ready();task(row,"daily",0,"daily","admin","pending");var daily=tasks.findById("daily").block(WAIT);daily.getDetails().setStartTime(System.currentTimeMillis()-10000);daily.getDetails().setEndTime(System.currentTimeMillis()+100000);daily.getDetails().setInspectionRoles(List.of(new DecorationModels.InspectionRole("inspect", "巡检角色", 1)));daily.getDetails().setReviewRoleId("review");tasks.save(daily).block(WAIT);
        var feedback=feedback(row,"daily");feedback.setRemark("巡查原始问题");feedback.setPassed(false);var violation=new DecorationModels.Violation();violation.setLocation("阳台");violation.setRectification("恢复外立面");feedback.setViolations(List.of(violation));row=workflow.inspectionFeedback(row.getId(),feedback).contextWrite(auth("admin","inspect")).block(WAIT);
        var record=tasks.createQuery().where("kind","inspectionRecord").fetchOne().block(WAIT);
        assertEquals("owner_rectification",record.getStatus());
        assertEquals("review",record.getDetails().getReviewRoleId());
        assertTrue(record.getDetails().getHandlingRecords().isEmpty());
        var visible=workflow.query(row.getId(),new QueryParamEntity(),true).contextWrite(auth("owner")).block(WAIT);
        assertTrue(visible.getData().stream().anyMatch(t->t.getId().equals(record.getId())&&"owner_rectification".equals(t.getStatus())));
        var correction=rectify(row,record.getId());correction.setRemark("已恢复");row=workflow.submitRectification(row.getId(),correction,true).contextWrite(auth("owner")).block(WAIT);
        var review=rectify(row,record.getId());review.setPassed(true);review.setRemark("复核已恢复");row=workflow.reviewRectification(row.getId(),review).contextWrite(auth("reviewer","review")).block(WAIT);
        assertEquals("rectified",tasks.findById(record.getId()).block(WAIT).getStatus());assertNull(row.getActualEndTime());
        var details=tasks.findById(record.getId()).block(WAIT).getDetails();
        assertEquals("巡查原始问题",details.getFeedback());
        assertEquals(List.of("已恢复","复核已恢复"),details.getHandlingRecords().stream().map(DecorationModels.TaskHandlingRecord::remark).toList());
        assertEquals(List.of("owner","reviewer"),details.getHandlingRecords().stream().map(DecorationModels.TaskHandlingRecord::operatorName).toList());
        assertTrue(details.getHandlingRecords().stream().allMatch(event->event.time()>=record.getCreateTime()));
    }
    @Test void rejectedRectificationResubmissionPreservesFilesActorsAndRoleSnapshots(){
        var row=ready();task(row,"correction",0,"inspectionRecord","admin","owner_rectification");
        var record=tasks.findById("correction").block(WAIT);record.getDetails().setFeedback("原始巡查意见");
        record.getDetails().setReviewRoleId("review");record.getDetails().setReviewRoleName("原复核角色");tasks.save(record).block(WAIT);
        file("first-photo","owner","project",row.getId());file("second-photo","owner","project",row.getId());
        var first=rectify(row,"correction");first.setRemark("第一次整改");first.setFileIds(List.of("first-photo"));
        row=workflow.submitRectification(row.getId(),first,true).contextWrite(auth("owner")).block(WAIT);
        workflow.submitRectification(row.getId(),first,true).contextWrite(auth("owner")).block(WAIT);
        var reject=rectify(row,"correction");reject.setPassed(false);reject.setRemark("仍需整改");
        row=workflow.reviewRectification(row.getId(),reject).contextWrite(auth("reviewer","review")).block(WAIT);
        when(roles.require("new-review")).thenReturn(Mono.just(new DecorationRoleService.Role("new-review","新复核角色")));
        var reassign=rectify(row,"correction");reassign.setReviewRoleId("new-review");
        row=workflow.reassignRole(row.getId(),reassign).contextWrite(auth("admin")).block(WAIT);
        var second=rectify(row,"correction");second.setRemark("第二次整改");second.setFileIds(List.of("second-photo"));
        row=workflow.submitRectification(row.getId(),second,true).contextWrite(auth("owner")).block(WAIT);
        var pass=rectify(row,"correction");pass.setPassed(true);pass.setRemark("通过");
        assertCode(workflow.reviewRectification(row.getId(),pass),"role_required","reviewer");
        row=workflow.reviewRectification(row.getId(),pass).contextWrite(auth("new-reviewer","new-review")).block(WAIT);
        var details=tasks.findById("correction").block(WAIT).getDetails();var records=details.getHandlingRecords();
        assertEquals("原始巡查意见",details.getFeedback());assertEquals("第二次整改",details.getRectificationRemark());
        assertEquals(5,records.size());assertEquals(List.of("first-photo"),records.get(0).fileIds());
        assertEquals("rectification_rejected",records.get(1).status());assertEquals("reviewer",records.get(1).operatorId());
        assertEquals("原复核角色",records.get(1).reviewRoleName());assertEquals("新复核角色",records.get(2).reviewRoleName());
        assertEquals(List.of("second-photo"),records.get(3).fileIds());assertEquals("rectified",records.get(4).status());
        assertEquals("new-reviewer",records.get(4).operatorName());
    }
    @Test void notificationUnavailableAndResendPreserveOldRectification(){
        var row=ready(); task(row,"old-rectification",0,"inspectionRecord","admin","rectified");
        var old=tasks.findById("old-rectification").block(WAIT);old.getDetails().setFeedback("原巡查内容");old.getDetails().setRectificationRemark("原整改内容");tasks.save(old).block(WAIT);
        var input=rectify(row,"old-rectification");input.setRemark("重新整改");row=workflow.resendRectification(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("reissued",tasks.findById("old-rectification").block(WAIT).getStatus());
        assertEquals("原整改内容",tasks.findById("old-rectification").block(WAIT).getDetails().getRectificationRemark());
        var fresh=tasks.createQuery().where("sourceTaskId","old-rectification").fetchOne().block(WAIT);
        assertNotNull(fresh);assertEquals("owner_rectification",fresh.getStatus());assertNull(fresh.getDetails().getRectificationRemark());
        assertEquals("原巡查内容",fresh.getDetails().getFeedback());assertEquals("重新整改",fresh.getDetails().getHandlingRecords().get(0).remark());
        assertEquals("reissued",tasks.findById("old-rectification").block(WAIT).getDetails().getHandlingRecords().get(0).status());
        assertCode(workflow.notifyViolation(row.getId(),rectify(row,fresh.getId())),"notification_unavailable","admin");
        assertCode(workflow.resendRectification(row.getId(),rectify(row,"old-rectification")),"invalid_state","admin");
    }
    @Test void externalFinanceReceiptValidatesDirectionAndPreventsReuse(){
        var row=approveSelf(createSigned(create("finance"),false).contextWrite(auth("admin")).block(WAIT));
        var review=new DecorationRequests.Review();review.setRequestId("review");review.setVersion(row.getVersion());review.setItemId("deposit");review.setResult(1);review.setAmount(new BigDecimal("1000"));
        row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
        var gateway=mock(DecorationFinanceGateway.class);var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("finance",gateway);
        var integrated=new DecorationFinanceService(beans.getBeanProvider(DecorationFinanceGateway.class),applications,financeReceipts,beans.getBeanProvider(DecorationNotificationGateway.class));
        when(gateway.payment(any())).thenAnswer(call->{var request=call.<DecorationFinanceGateway.Request>getArgument(0);return Mono.just(financeResult(request,DecorationFinanceGateway.Direction.REFUND,"receipt"));});
        var payment=new DecorationRequests.Finance();payment.setRequestId("pay");payment.setVersion(row.getVersion());payment.setReference("receipt");
        assertCode(integrated.process(row.getId(),payment,false,false),"finance_mismatch","admin");assertEquals(0,financeReceipts.createQuery().count().block(WAIT));
        doAnswer(call->Mono.just(financeResult(call.getArgument(0),DecorationFinanceGateway.Direction.PAYMENT,"receipt"))).when(gateway).payment(any());
        row=integrated.process(row.getId(),payment,false,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals("pending_permit",row.getStatus());assertEquals(2,row.getPaymentStatus());assertEquals(1,financeReceipts.createQuery().count().block(WAIT));
        row.setStatus("acceptance_passed");rows.save(row).block(WAIT);
        var refund=new DecorationRequests.Finance();refund.setRequestId("refund");refund.setVersion(row.getVersion());refund.setReference("refund-receipt");
        when(gateway.refund(any())).thenAnswer(call->Mono.just(financeResult(call.getArgument(0),DecorationFinanceGateway.Direction.REFUND,"refund-receipt")));
        row=integrated.process(row.getId(),refund,true,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals("finished",row.getStatus());assertEquals(2,row.getRefundStatus());assertNull(row.getActiveHouseKey());assertNotNull(row.getActualEndTime());
    }
    @Test void virtualFinanceButtonsRequireSimulationAndPreserveOwnerStateAndReplayChecks() {
        var row=createSigned(create("virtual-buttons"),false).contextWrite(auth("admin")).block(WAIT);
        var input=new DecorationRequests.Action();input.setRequestId("virtual-pay");input.setVersion(row.getVersion());
        assertCode(finance.simulate(row.getId(),input,false,true),"simulation_unavailable","owner");
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var real=mock(DecorationFinanceGateway.class);beans.registerSingleton("real",real);
        var realOnly=new DecorationFinanceService(beans.getBeanProvider(DecorationFinanceGateway.class),applications,financeReceipts,beans.getBeanProvider(DecorationNotificationGateway.class));
        assertCode(realOnly.simulate(row.getId(),input,false,true),"simulation_unavailable","owner");verifyNoInteractions(real);
        var simulated=mock(org.jetlinks.pro.property.decoration.integration.SimulatedDecorationGateway.class);
        beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("simulated",simulated);
        var virtual=new DecorationFinanceService(beans.getBeanProvider(DecorationFinanceGateway.class),applications,financeReceipts,beans.getBeanProvider(DecorationNotificationGateway.class));
        assertCode(virtual.simulate(row.getId(),input,false,true),"review_required","owner");
        row.getDetails().getSelfInspectionReview().setReviewStatus(1);row.setDepositReviewStatus(1);row.setApprovedDeposit(new BigDecimal("1000"));row.setStatus("pending_payment");rows.save(row).block(WAIT);
        assertCode(virtual.simulate(row.getId(),input,false,true),"not_found","other");
        when(simulated.payment(any())).thenAnswer(call->{var request=call.<DecorationFinanceGateway.Request>getArgument(0);
            assertTrue(request.offline());assertTrue(request.reference().startsWith("SIM-PAYMENT-"));
            return Mono.just(financeResult(request,DecorationFinanceGateway.Direction.PAYMENT,request.reference()));});
        row=virtual.simulate(row.getId(),input,false,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals("pending_permit",row.getStatus());assertEquals(2,row.getPaymentStatus());
        var replay=virtual.simulate(row.getId(),input,false,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(row.getVersion(),replay.getVersion());verify(simulated,times(1)).payment(any());
        var refund=new DecorationRequests.Action();refund.setRequestId("virtual-refund");refund.setVersion(row.getVersion());
        assertCode(virtual.simulate(row.getId(),refund,true,true),"refund_not_allowed","owner");
        row.setStatus("acceptance_passed");rows.save(row).block(WAIT);
        when(simulated.refund(any())).thenAnswer(call->{var request=call.<DecorationFinanceGateway.Request>getArgument(0);
            assertTrue(request.reference().startsWith("SIM-REFUND-"));
            return Mono.just(financeResult(request,DecorationFinanceGateway.Direction.REFUND,request.reference()));});
        row=virtual.simulate(row.getId(),refund,true,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals("finished",row.getStatus());assertEquals(0,new BigDecimal("1000").compareTo(row.getRefundedAmount()));
        assertEquals(1,row.getDetails().getRefunds().size());assertEquals("single",row.getDetails().getRefunds().get(0).getStage());
        virtual.simulate(row.getId(),refund,true,true).contextWrite(auth("owner")).block(WAIT);
        verify(simulated,times(1)).refund(any());assertEquals(2,financeReceipts.createQuery().count().block(WAIT));
    }
    @Test void singleRefundWaitsForSecondAcceptanceAndSettlesOnce() {
        var row=createSigned(create("single-refund"),false).contextWrite(auth("admin")).block(WAIT);
        row.getDetails().getSelfInspectionReview().setReviewStatus(1);row.setPaymentStatus(2);row.setPaidAmount(new BigDecimal("1000"));row.setStatus("pending_second_acceptance");
        row.getDetails().getAgreement().setRefundAmount(new BigDecimal("900"));
        row.getDetails().setFirstAcceptancePassedTime(System.currentTimeMillis());rows.save(row).block(WAIT);
        var gateway=mock(DecorationFinanceGateway.class);var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("finance",gateway);
        var integrated=new DecorationFinanceService(beans.getBeanProvider(DecorationFinanceGateway.class),applications,financeReceipts,beans.getBeanProvider(DecorationNotificationGateway.class));
        var amounts=new java.util.ArrayList<BigDecimal>();
        when(gateway.refund(any())).thenAnswer(call->{var request=call.<DecorationFinanceGateway.Request>getArgument(0);amounts.add(request.amount());return Mono.just(financeResult(request,DecorationFinanceGateway.Direction.REFUND,request.reference()));});
        var refund=new DecorationRequests.Finance();refund.setRequestId("settle-single-refund");refund.setVersion(row.getVersion());refund.setReference("single-receipt");
        assertCode(integrated.process(row.getId(),refund,true,false),"refund_not_allowed","admin");
        assertTrue(amounts.isEmpty());assertEquals(0,rows.findById(row.getId()).block(WAIT).getRefundStatus());
        row.setStatus("acceptance_passed");rows.save(row).block(WAIT);
        row=integrated.process(row.getId(),refund,true,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals(List.of(new BigDecimal("900")),amounts);assertEquals(new BigDecimal("900"),row.getRefundedAmount());
        assertEquals("finished",row.getStatus());assertEquals(2,row.getRefundStatus());assertNotNull(row.getActualEndTime());assertNull(row.getActiveHouseKey());
        assertEquals(1,row.getDetails().getRefunds().size());assertEquals("single",row.getDetails().getRefunds().get(0).getStage());
        integrated.process(row.getId(),refund,true,false).contextWrite(auth("admin")).block(WAIT);assertEquals(1,amounts.size());
    }
    @Test void depositReviewCannotApproveLessThanFrozenSingleRefundTotal() {
        var row=createSigned(create("review-single-total"),false).contextWrite(auth("admin")).block(WAIT);
        row.getDetails().getAgreement().setRefundAmount(new BigDecimal("900"));rows.save(row).block(WAIT);
        var review=new DecorationRequests.Review();review.setRequestId("approve-single-total");review.setVersion(row.getVersion());review.setItemId("deposit");review.setResult(1);review.setAmount(new BigDecimal("800"));
        assertCode(reviews.review(row.getId(),review),"invalid_refund_amount","admin");
        assertEquals(0,rows.findById(row.getId()).block(WAIT).getDepositReviewStatus());
        review.setAmount(new BigDecimal("900"));
        row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
        assertEquals(new BigDecimal("900"),row.getApprovedDeposit());assertEquals(1,row.getDepositReviewStatus());
    }
    @Test void configurationRefundTotalRejectsNegativeExcessAndFractionalCents() {
        var input=config("agreement");input.setVersion(1);
        for(String invalid:List.of("-1","1001","0.001")) {
            input.getContent().getAgreement().setRefundAmount(new BigDecimal(invalid));
            assertCode(configurations.save(input),"invalid_refund_amount","admin");
        }
        input.getContent().getAgreement().setRefundAmount(new BigDecimal("800.50"));
        var saved=configurations.save(input).contextWrite(auth("admin")).block(WAIT);
        assertEquals(new BigDecimal("800.50"),saved.getContent().getAgreement().getRefundAmount());
    }
    @Test void singleRefundSupportsLegacyTotalAndAlreadySettledAmounts() {
        var row=new DecorationApplicationEntity();row.setDetails(new DecorationModels.ApplicationDetails());row.getDetails().setAgreement(new DecorationModels.Agreement());row.setPaymentStatus(2);row.setRefundStatus(0);row.setPaidAmount(new BigDecimal("1000"));row.setStatus("acceptance_passed");
        assertEquals(new BigDecimal("1000"),DecorationFinanceService.refundPlan(row).amount());
        row.getDetails().getAgreement().setFirstRefund(new BigDecimal("600"));row.getDetails().getAgreement().setSecondRefund(new BigDecimal("400"));
        assertEquals(new BigDecimal("1000"),DecorationFinanceService.refundPlan(row).amount());
        row.setRefundedAmount(new BigDecimal("600"));
        assertEquals(new BigDecimal("400"),DecorationFinanceService.refundPlan(row).amount());
        row.getDetails().getAgreement().setRefundAmount(new BigDecimal("500"));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,()->DecorationFinanceService.refundPlan(row));
        row.getDetails().getAgreement().setRefundAmount(new BigDecimal("1001"));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,()->DecorationFinanceService.refundPlan(row));
        row.getDetails().getAgreement().setRefundAmount(new BigDecimal("600"));
        var plan=DecorationFinanceService.refundPlan(row);assertEquals("single",plan.stage());assertEquals(BigDecimal.ZERO,plan.amount());assertTrue(plan.last());
    }
    @Test void numberChecksRejectFalsePassAndAllowRecordedFailure(){
        var item=new DecorationModels.CheckItem();item.setId("width");item.setName("宽度");item.setInputType("number");item.setRequired(true);item.setMin(new BigDecimal("10"));item.setMax(new BigDecimal("20"));
        var result=new DecorationModels.CheckResult();result.setItemId("width");result.setValue("25");result.setQualified(true);
        assertThrows(org.hswebframework.web.exception.BusinessException.class,()->DecorationRules.checks(List.of(item),List.of(result)));
        result.setQualified(false);result.setRectification("整改至范围内");result.setDeadline(System.currentTimeMillis()+86400000);DecorationRules.checks(List.of(item),List.of(result));
    }
    private DecorationFinanceGateway.Result financeResult(DecorationFinanceGateway.Request request,DecorationFinanceGateway.Direction direction,String reference){
        return new DecorationFinanceGateway.Result(request.decorationId(),request.applicationId(),request.tenantId(),request.projectId(),request.spaceId(),request.ownerUserId(),request.chargeItemId(),request.amount(),"provider",reference,direction,request.offline(),DecorationFinanceGateway.Status.SUCCEEDED,System.currentTimeMillis());
    }
    @Test void queryOriginalRequestIsReadOnly(){
        var input=create("one");var row=createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(row.getId(),applications.creationResult("one",true).contextWrite(auth("owner")).block(WAIT).getId());
        assertCode(applications.actionResult(row.getId(),"missing",true),"request_not_found","owner");
        assertEquals(1,histories.createQuery().count().block(WAIT));
    }
    @Test void ownerSelfInspectionIsCapturedOnceAndDeletionPreservesApplicationAndFiles(){
        var input=create("owner-self"); input.getSelfInspectionItems().get(0).setValue(false); input.setSelfInspectionStatus(0);
        var application=createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,selfInspectionRows.createQuery().count().block(WAIT));
        var self=selfInspections.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getData().get(0);
        assertEquals(application.getId(),self.getDecorationId()); assertEquals("owner",self.getCreatorName());
        String image=application.getDetails().getSignedDocuments().stream().filter(d->"selfInspection".equals(d.getKind())).findFirst().orElseThrow().getDocumentFileId();
        assertEquals(9,application.getDetails().getSelfInspectionItems().size());
        assertEquals(1,self.getStatus()); assertNull(self.getRemark()); assertEquals(List.of(image),self.getFileIds());
        assertEquals(application.getId(),files.findById(image).block(WAIT).getDecorationId());
        assertCode(selfInspections.delete(self.getId(),2),"version_conflict","admin");
        selfInspections.delete(self.getId(),1).contextWrite(auth("admin")).block(WAIT);
        assertCode(selfInspections.read(self.getId()),"not_found","admin");
        assertEquals(0,selfInspections.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).getTotal());
        assertEquals(List.of(image),applications.read(application.getId(),false).contextWrite(auth("admin")).block(WAIT).getDetails().getSelfInspectionFiles());
        assertEquals(1,histories.createQuery().count().block(WAIT));
        assertEquals(application.getId(),files.findById(image).block(WAIT).getDecorationId());
        createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,selfInspectionRows.createQuery().count().block(WAIT));
        assertTrue(selfInspectionRows.findById(self.getId()).block(WAIT).getDeleted());
    }
    @Test void pcDoesNotProduceSelfInspectionAndConcurrentOwnerCreationHasNoOrphans(){
        var pc=createSigned(create("pc"),false).contextWrite(auth("admin")).block(WAIT);
        assertEquals(0,selfInspectionRows.createQuery().count().block(WAIT));
        assertTrue(pc.getDetails().getSelfInspectionItems().isEmpty());
        assertEquals(1,pc.getDetails().getSelfInspectionFiles().size());
        assertTrue(pc.getDetails().getSignedDocuments().isEmpty());
        // 独立创建业主并发样例；PC申请不得通过手动结束释放房屋。
        rows.deleteById(pc.getId()).block(WAIT);
        var results=Flux.merge(createSigned(create("owner-one"),true).map(r->true).onErrorReturn(false),
            createSigned(create("owner-two"),true).map(r->true).onErrorReturn(false)).collectList().contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());
        var self=selfInspectionRows.createQuery().fetchOne().block(WAIT);
        assertEquals(1,selfInspectionRows.createQuery().count().block(WAIT));
        assertEquals("h5_owner",rows.findById(self.getDecorationId()).block(WAIT).getSource());
    }
    @Test void selfInspectionPaginationFiltersAndOrQueriesCannotEscapeScope(){
        var application=createSigned(create("owner"),true).contextWrite(auth("owner")).block(WAIT);
        var original=selfInspectionRows.createQuery().fetchOne().block(WAIT);
        for(int i=1;i<25;i++){
            var row=new ObjectMapper().convertValue(original,DecorationSelfInspectionEntity.class);
            row.setId("self-"+i);row.setDecorationId("application-"+i);row.setSpaceName(i==24?"二栋202":"一栋101");row.setCreateTime(original.getCreateTime()+i);
            selfInspectionRows.insert(row).block(WAIT);
        }
        for(String boundary:List.of("application","tenant","project")){
            var row=new ObjectMapper().convertValue(original,DecorationSelfInspectionEntity.class);row.setId("foreign-"+boundary);row.setDecorationId(row.getId());
            switch(boundary){case "application"->row.setApplicationId("other");case "tenant"->row.setTenantId("other");case "project"->row.setProjectId("other");}
            selfInspectionRows.insert(row).block(WAIT);
            assertCode(selfInspections.read(row.getId()),"not_found","admin");
            assertCode(selfInspections.delete(row.getId(),1),"not_found","admin");
        }
        var query=QueryParamEntity.newQuery().where("id","foreign-application").or("spaceName","like","%101%").getParam();query.setPageSize(20);
        var first=selfInspections.query(query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(24,first.getTotal());assertEquals(20,first.getData().size());
        query.setPageIndex(1);var second=selfInspections.query(query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(4,second.getData().size());
        assertTrue(first.getData().stream().noneMatch(a->second.getData().stream().anyMatch(b->a.getId().equals(b.getId()))));
        var exact=QueryParamEntity.newQuery().where("spaceName","like","%202%").and("createTime","gte",original.getCreateTime()+24).getParam();
        assertEquals(1,selfInspections.query(exact).contextWrite(auth("admin")).block(WAIT).getTotal());
        assertEquals(application.getId(),selfInspections.read(original.getId()).contextWrite(auth("admin")).block(WAIT).getDecorationId());
    }
    @Test void overviewUsesDistinctHousesLatestRefundAndNaturalMonthComparison(){
        var base=createSigned(create("statistics"),false).contextWrite(auth("admin")).block(WAIT);
        rows.deleteById(base.getId()).block(WAIT);
        long current=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long previous=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).minusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        var a1=overviewRow(base,"a-old","a","residential",previous+1,2,2,1,"100");a1.setRefundTime(current+1);a1.setActualEndTime(current+1);
        var a2=overviewRow(base,"a-new","a","residential",current+2,0,0,0,"0");a2.getDetails().setMaterials(List.of(overviewMaterial("material",0),overviewMaterial("drawing",0)));
        var b=overviewRow(base,"b","b","commercial",current+1,2,2,1,"200");b.setRefundTime(current+2);b.setActualEndTime(current+2);
        var c=overviewRow(base,"c","c","residential",previous+2,2,0,1,"300");c.setStatus("finished");c.setActualEndTime(current+1);c.setActiveHouseKey(null);
        var d=overviewRow(base,"d","d","commercial",current+3,0,0,1,"0");d.setStatus("pending_payment");
        var e=overviewRow(base,"e","e","residential",current+4,0,0,1,"0");e.getDetails().setMaterials(List.of(overviewMaterial("drawing",0)));
        var f=overviewRow(base,"f","f","residential",current+5,0,0,1,"0");f.getDetails().setMaterials(List.of(overviewMaterial("material",0)));
        var g=overviewRow(base,"g","g","commercial",current+6,0,0,2,"0");g.setStatus("rejected");g.getDetails().setMaterials(List.of(overviewMaterial("drawing",2)));
        var h=overviewRow(base,"h","h","commercial",current+7,2,0,1,"400");
        rows.insert(Flux.fromIterable(List.of(a1,a2,b,c,d,e,f,g,h))).block(WAIT);
        var report=overview.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(8,report.summary().totalHouseCount());assertEquals(7,report.summary().inDecorationHouseCount());assertEquals(1,report.summary().completedHouseCount());
        assertEquals(0,new BigDecimal("250").compareTo(report.summary().totalHouseCountRate()));
        assertEquals(0,new BigDecimal("500").compareTo(report.summary().inDecorationHouseCountRate()));
        assertEquals(0,new BigDecimal("100").compareTo(report.summary().completedHouseCountRate()));
        assertEquals(0,new BigDecimal("1000").compareTo(report.summary().paidDepositAmount()));
        assertEquals(0,new BigDecimal("50").compareTo(report.summary().paidDepositAmountRate()));
        assertEquals(List.of(2L,2L,1L,1L,1L),report.funnel().stream().map(DecorationOverviewService.Item::value).toList());
        assertEquals(List.of(new DecorationOverviewService.Item("commercial",4),new DecorationOverviewService.Item("residential",4)),report.houseTypePie());
        var none=overview.query(QueryParamEntity.newQuery().where("projectId","denied").getParam()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(0,none.summary().totalHouseCount());assertEquals(0,none.summary().totalHouseCountRate().signum());assertTrue(none.houseTypePie().isEmpty());
    }
    @Test void overviewLatestTieAndOrFiltersRemainWithinApplicationTenantAndProjects(){
        var base=createSigned(create("tie"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        var old=overviewRow(base,"a-old","house","residential",1,2,2,1,"100");old.setActualEndTime(1L);old.setRefundTime(1L);
        var latest=overviewRow(base,"z-latest","house","residential",1,0,0,0,"0");
        var foreign=overviewRow(base,"zz-foreign","house","residential",2,2,2,1,"900");foreign.setApplicationId("foreign");
        var tenant=overviewRow(base,"zz-tenant","house","residential",2,2,2,1,"900");tenant.setTenantId("foreign");
        var denied=overviewRow(base,"zz-project","denied-house","residential",2,2,2,1,"900");denied.setProjectId("denied");
        rows.insert(Flux.fromIterable(List.of(old,latest,foreign,tenant,denied))).block(WAIT);
        var query=QueryParamEntity.newQuery().where("id","zz-foreign").or("spaceId","house").getParam();
        var report=overview.query(query).contextWrite(auth("admin")).block(WAIT);
        assertEquals(1,report.summary().totalHouseCount());assertEquals(1,report.summary().inDecorationHouseCount());assertEquals(0,report.summary().completedHouseCount());
        assertEquals(0,new BigDecimal("100").compareTo(report.summary().paidDepositAmount()));
        assertEquals(0,report.summary().totalHouseCountRate().signum());
    }
    @Test void overviewMonthRangesAreHalfOpenAndRatesMayBeNegative(){
        var base=createSigned(create("month-ranges"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        var zone=java.time.ZoneId.of("Asia/Shanghai");var month=java.time.YearMonth.now(zone);
        long current=month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),previous=month.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),next=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        rows.insert(Flux.fromIterable(List.of(
            overviewRow(base,"previous-start","one","residential",previous,0,0,0,"0"),
            overviewRow(base,"previous-end","two","residential",current-1,0,0,0,"0"),
            overviewRow(base,"current-start","three","residential",current,0,0,0,"0"),
            overviewRow(base,"next-start","four","residential",next,0,0,0,"0"),
            overviewRow(base,"before-previous","five","residential",previous-1,0,0,0,"0")
        ))).block(WAIT);
        var summary=overview.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).summary();
        assertEquals(5,summary.totalHouseCount());assertEquals(0,new BigDecimal("-50").compareTo(summary.totalHouseCountRate()));
        assertEquals(0,new BigDecimal("-50").compareTo(summary.inDecorationHouseCountRate()));assertEquals(0,summary.paidDepositAmountRate().signum());
    }
    @Test void overviewAggregatesTenThousandSnapshotsWithoutLoadingTheApplicationPage(){
        var base=createSigned(create("scale"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        var snapshots=java.util.stream.IntStream.range(0,10000).mapToObj(i->overviewRow(base,"scale-"+i,"house-"+i,"residential",1L+i,0,0,1,"0")).toList();
        rows.insert(Flux.fromIterable(snapshots)).block(Duration.ofSeconds(60));
        long start=System.nanoTime();var report=overview.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertEquals(10000,report.summary().totalHouseCount());assertEquals(10000,report.summary().inDecorationHouseCount());
        assertEquals(10000,report.funnel().get(2).value());assertEquals(10000,report.houseTypePie().get(0).value());
        assertTrue(elapsed<10000,"aggregate elapsed "+elapsed+"ms");System.out.println("decoration overview 10000 snapshots: "+elapsed+"ms");
    }
    @Test void dashboardUsesUnifiedReviewAndDrillDownWithinScope(){
        var base=createSigned(create("dashboard"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        long now=System.currentTimeMillis();
        var review=overviewRow(base,"review","review-house","residential",now-1000,0,0,0,"0");
        review.getDetails().setMaterials(List.of(overviewMaterial("material",0),overviewMaterial("drawing",0)));
        var rejected=overviewRow(base,"rejected","rejected-house","residential",now-1000,0,0,0,"0");rejected.setStatus("rejected");
        var ended=overviewRow(base,"ended","ended-house","residential",now-1000,2,2,1,"100");ended.setActualEndTime(now-500);
        var old=overviewRow(base,"old","review-house","residential",1,2,2,1,"100");old.setActualEndTime(1L);
        var foreign=overviewRow(base,"foreign","review-house","residential",now,0,0,0,"0");foreign.setApplicationId("foreign");
        var tenant=overviewRow(base,"tenant","review-house","residential",now,0,0,0,"0");tenant.setTenantId("foreign");
        var denied=overviewRow(base,"denied","denied-house","residential",now,0,0,0,"0");denied.setProjectId("denied");
        rows.insert(Flux.just(review,rejected,ended,old,foreign,tenant,denied)).block(WAIT);
        var report=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        var counts=report.metrics().stream().collect(java.util.stream.Collectors.toMap(DecorationDashboardService.Metric::key,DecorationDashboardService.Metric::value));
        assertEquals(1L,counts.get("pendingReview"));assertEquals(1L,counts.get("rejected"));
        assertEquals(3L,counts.get("totalHouses"));assertEquals(3L,counts.get("monthNew"));assertEquals(1L,counts.get("monthEnded"));assertEquals(0L,counts.get("decorating"));
        for(var metric:report.metrics()){
            var query=QueryParamEntity.newQuery().where("id",DecorationOverviewMetricTermBuilder.TYPE,Map.of("metric",metric.key(),"asOf",report.asOf())).getParam();
            var page=applications.query(query,false).contextWrite(auth("admin")).block(WAIT);
            assertEquals(metric.value(),page.getTotal(),metric.key());
        }
        var or=QueryParamEntity.newQuery().where("id","review").or("applicationId","foreign").getParam();
        assertEquals(1L,dashboard.query(or).contextWrite(auth("admin")).block(WAIT).metrics().stream().filter(m->m.key().equals("pendingReview")).findFirst().orElseThrow().value());
    }

    @Test void dashboardKeepsAcceptanceAndPendingRefundHousesUntilRefundCompletes(){
        var base=createSigned(create("dashboard-lifecycle"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        long now=System.currentTimeMillis();
        var statuses=List.of("permit_issued","pending_second_acceptance","second_acceptance_waiting","second_acceptance_in_progress","second_acceptance_failed","acceptance_passed");
        for(int index=0;index<statuses.size();index++){
            var row=overviewRow(base,"lifecycle-"+index,"house-"+index,"residential",now-1000,2,0,1,"100");
            row.setStatus(statuses.get(index));row.setStartTime(now-2000);
            var permit=new DecorationModels.Permit();permit.setPrintTime(now-1000);row.setPermit(permit);
            rows.insert(row).block(WAIT);
        }
        var completed=overviewRow(base,"lifecycle-completed","completed-house","residential",now-1000,2,2,1,"100");
        completed.setStatus("finished");completed.setActualEndTime(now);rows.insert(completed).block(WAIT);
        var report=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(6L,report.metrics().stream().filter(metric->metric.key().equals("decorating")).findFirst().orElseThrow().value());
        var query=QueryParamEntity.newQuery().where("id",DecorationOverviewMetricTermBuilder.TYPE,Map.of("metric","decorating","asOf",report.asOf())).getParam();
        assertEquals(6L,applications.query(query,false).contextWrite(auth("admin")).block(WAIT).getTotal());
    }

    @Test void dashboardCountsPrintOrDownloadAsCompletedAndKeepsExtensionPending(){
        var base=createSigned(create("dashboard-permit"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        long now=System.currentTimeMillis();
        var row=overviewRow(base,"active","house","residential",now-1000,2,0,1,"100");row.setStatus("permit_issued");row.setStartTime(now-2000);
        var permit=new DecorationModels.Permit();permit.setPrintTime(now-1000);permit.setCollectedTime(now-500);permit.setEndTime(now+86400000L);row.setPermit(permit);
        rows.insert(row).block(WAIT);task(row,"daily-one",1,"daily","admin","pending");task(row,"daily-two",2,"daily","admin","pending");task(row,"rectify",1,"inspectionRecord","admin","waiting_review");
        var report=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        var counts=report.metrics().stream().collect(java.util.stream.Collectors.toMap(DecorationDashboardService.Metric::key,DecorationDashboardService.Metric::value));
        assertEquals(1L,counts.get("decorating"));assertEquals(1L,counts.get("pendingInspection"));assertEquals(1L,counts.get("pendingRectificationReview"));assertEquals(1L,counts.get("expiring"));assertEquals(0L,counts.get("pendingPermit"));
        // 打印完成不再等待领取，下载单独完成也使用相同统计口径。
        permit.setCollectedTime(null);permit.setPrints(List.of(new DecorationModels.PermitPrint("snapshot","digest",permit.getEndTime(),"admin","Admin",now)));rows.save(row).block(WAIT);
        var printed=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).metrics().stream().collect(java.util.stream.Collectors.toMap(DecorationDashboardService.Metric::key,DecorationDashboardService.Metric::value));
        assertEquals(1L,printed.get("decorating"));assertEquals(0L,printed.get("pendingPermit"));
        permit.setPrintTime(null);permit.setDownloadTime(now);rows.save(row).block(WAIT);
        var downloaded=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).metrics().stream().collect(java.util.stream.Collectors.toMap(DecorationDashboardService.Metric::key,DecorationDashboardService.Metric::value));
        assertEquals(1L,downloaded.get("decorating"));assertEquals(0L,downloaded.get("pendingPermit"));
        // 延期清除当前版本输出时间后，应同时提示备案办理及更新证件。
        permit.setCollectedTime(null);permit.setPrintTime(null);permit.setDownloadTime(null);permit.setExtensions(List.of(new DecorationModels.Extension()));row.setStatus("pending_permit");rows.save(row).block(WAIT);
        counts=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).metrics().stream().collect(java.util.stream.Collectors.toMap(DecorationDashboardService.Metric::key,DecorationDashboardService.Metric::value));
        assertEquals(0L,counts.get("decorating"));assertEquals(1L,counts.get("pendingPermit"));assertEquals(1L,counts.get("renewal"));
        var t=tasks.findById("rectify").block(WAIT);tasks.deleteById(t.getId()).block(WAIT);t.setTenantId("other");tasks.insert(t).block(WAIT);
        assertEquals(0L,dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT).metrics().stream().filter(m->m.key().equals("pendingRectificationReview")).findFirst().orElseThrow().value());
    }

    @Test void dashboardTrendUsesShanghaiMonthsZeroFillAndAuthorizedScope(){
        var base=createSigned(create("dashboard-trend"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        var month=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));
        var zone=java.time.ZoneId.of("Asia/Shanghai");
        long current=month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long next=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long first=month.minusMonths(11).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        var last=overviewRow(base,"last","last-house","residential",current-1,2,2,1,"100");last.setActualEndTime(current);
        var now=overviewRow(base,"now","now-house","residential",current,2,2,1,"100");now.setActualEndTime(next);
        var oldest=overviewRow(base,"oldest","oldest-house","residential",first,2,2,1,"100");oldest.setActualEndTime(first-1);
        var before=overviewRow(base,"before","before-house","residential",first-1,0,0,0,"0");before.setActualEndTime(current);
        var future=overviewRow(base,"future","future-house","residential",next,0,0,0,"0");
        var denied=overviewRow(base,"denied","denied-house","residential",current,2,2,1,"100");denied.setProjectId("denied");denied.setActualEndTime(current);
        var foreign=overviewRow(base,"foreign","foreign-house","residential",current,2,2,1,"100");foreign.setApplicationId("foreign");foreign.setActualEndTime(current);
        var tenant=overviewRow(base,"tenant","tenant-house","residential",current,2,2,1,"100");tenant.setTenantId("foreign");tenant.setActualEndTime(current);
        rows.insert(Flux.just(last,now,oldest,before,future,denied,foreign,tenant)).block(WAIT);
        var report=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(12,report.trend().size());
        assertEquals(new DecorationDashboardService.Month(month.minusMonths(11).toString(),1,0),report.trend().get(0));
        assertEquals(new DecorationDashboardService.Month(month.minusMonths(10).toString(),0,0),report.trend().get(1));
        assertEquals(new DecorationDashboardService.Month(month.minusMonths(1).toString(),1,0),report.trend().get(10));
        assertEquals(new DecorationDashboardService.Month(month.toString(),1,1),report.trend().get(11));
        var empty=dashboard.query(QueryParamEntity.newQuery().where("projectId","denied").getParam()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(12,empty.trend().size());assertTrue(empty.trend().stream().allMatch(m->m.created()==0&&m.ended()==0));
    }

    @Test void dashboardAggregatesTenThousandRowsWithoutLoadingDetails(){
        var base=createSigned(create("dashboard-scale"),false).contextWrite(auth("admin")).block(WAIT);rows.deleteById(base.getId()).block(WAIT);
        rows.insert(Flux.range(0,10000).map(i->overviewRow(base,"scale-"+i,"house-"+i,"residential",1L+i,0,0,0,"0"))).block(Duration.ofSeconds(60));
        long start=System.nanoTime();var report=dashboard.query(new QueryParamEntity()).contextWrite(auth("admin")).block(WAIT);
        assertEquals(10000L,report.metrics().stream().filter(m->m.key().equals("totalHouses")).findFirst().orElseThrow().value());
        assertEquals(10000L,report.metrics().stream().filter(m->m.key().equals("pendingReview")).findFirst().orElseThrow().value());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<10000);
    }
    private DecorationApplicationEntity overviewRow(DecorationApplicationEntity base,String id,String house,String houseType,long time,int payment,int refund,int deposit,String amount){
        var row=cloneRow(base);row.setId(id);row.setSpaceId(house);row.setHouseType(houseType);row.setCreateTime(time);row.setPaymentStatus(payment);row.setRefundStatus(refund);row.setDepositReviewStatus(deposit);
        row.setActiveHouseKey(refund==2?null:id);row.setPaidAmount(new BigDecimal(amount));row.setPaidTime(payment==2?time:null);row.setApprovedDeposit(new BigDecimal(amount));row.setStatus(refund==2?"finished":payment==2?"pending_permit":"reviewing");return row;
    }
    private DecorationModels.Material overviewMaterial(String kind,int status){
        var definition=new DecorationModels.MaterialDefinition();definition.setId(kind);definition.setKind(kind);definition.setRequired(true);definition.setEnabled(true);
        var material=new DecorationModels.Material();material.setDefinition(definition);material.setFileIds(List.of("photo"));material.setReviewStatus(status);return material;
    }
    @Test void attachmentMetadataUsesTheSameApplicationProjectAndOwnerBoundaryAsDownload() {
        var row=createSigned(create("metadata"),false).contextWrite(auth("admin")).block(WAIT);
        var id=row.getDetails().getSelfInspectionFiles().get(0);
        assertEquals(id,attachments.metadata(id).contextWrite(auth("admin")).block(WAIT).id());
        assertEquals(id,attachments.metadata(id).contextWrite(auth("owner")).block(WAIT).id());
        assertCode(attachments.metadata(id),"forbidden","other-owner");
        assertCode(attachments.wordText(id),"invalid_attachment","admin");
    }
    private DecorationApplicationEntity approveSelf(DecorationApplicationEntity row) {
        var review=new DecorationRequests.Review();review.setRequestId("approve-self");review.setVersion(row.getVersion());
        review.setItemId("self-inspection");review.setResult(1);
        return reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
    }
    @Test void selfInspectionIsReviewedIndividuallyAndRejectedFilesCanBeResubmitted() {
        var row=createSigned(create("self-review"),false).contextWrite(auth("admin")).block(WAIT);
        assertEquals(0,row.getDetails().getSelfInspectionReview().getReviewStatus());
        var review=new DecorationRequests.Review();review.setRequestId("reject-self");review.setVersion(row.getVersion());
        review.setItemId("self-inspection");review.setResult(2);review.setRemark("自检签字不清晰");
        row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
        assertEquals("rejected",row.getStatus());assertEquals("admin",row.getDetails().getSelfInspectionReview().getReviewerName());
        file("replacement-self","admin","project",row.getId());
        var upload=new DecorationRequests.MaterialUpload();upload.setItemId("self-inspection");upload.setFileIds(List.of("replacement-self"));
        var resubmit=new DecorationRequests.Resubmit();resubmit.setRequestId("resubmit-self");resubmit.setVersion(row.getVersion());resubmit.setMaterials(List.of(upload));
        row=reviews.resubmit(row.getId(),resubmit,false).contextWrite(auth("admin")).block(WAIT);
        assertEquals(List.of("replacement-self"),row.getDetails().getSelfInspectionFiles());
        assertEquals(0,row.getDetails().getSelfInspectionReview().getReviewStatus());assertNull(row.getDetails().getSelfInspectionReview().getReviewerId());
        assertEquals("reviewing",row.getStatus());
        row=approveSelf(row);assertEquals(1,row.getDetails().getSelfInspectionReview().getReviewStatus());
    }
    @Test void workerRegistrationIsIndependentAndKeepsIssuedCertificateSnapshots() {
        var row=ready();rows.createUpdate().setNull("permit").where("id",row.getId()).execute().block(WAIT);
        row=rows.findById(row.getId()).block(WAIT);
        var worker=new DecorationModels.Worker();worker.setName("施工人员");worker.setPhone("13900000003");worker.setCertificate("11010519491231002X");
        worker.setEntryTime(System.currentTimeMillis());worker.setExpirationTime(System.currentTimeMillis()+86400000L);
        var input=new DecorationRequests.RegisterWorkers();input.setRequestId("register-workers");input.setVersion(row.getVersion());
        input.setSafetyPerson("安全负责人");input.setSafetyPhone("13900000002");input.setContent("室内装修");input.setWorkers(List.of(worker));
        row=permits.registerWorkers(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertNull(row.getPermit());assertEquals("admin",row.getDetails().getWorkerRegistration().getOperatorName());
        assertEquals(1,row.getDetails().getWorkerRegistration().getWorkers().size());
        assertEquals(row.getVersion(),permits.registerWorkers(row.getId(),input).contextWrite(auth("admin")).block(WAIT).getVersion());
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("admin","管理员","13900000001",List.of("project"))));
        var issue=new DecorationRequests.IssuePermit();issue.setRequestId("issue-after-registration");issue.setVersion(row.getVersion());
        row=permits.issue(row.getId(),issue).contextWrite(auth("admin")).block(WAIT);
        assertEquals("室内装修",row.getPermit().getContent());assertEquals(1,row.getPermit().getWorkers().size());
        long collected=System.currentTimeMillis();
        row.getPermit().getPrints().add(new DecorationModels.PermitPrint("frozen","seal",row.getPermit().getEndTime(),"admin","管理员",collected));
        row.getPermit().setPrintTime(collected);row.getPermit().setCollectedTime(collected);row.getPermit().setCollectedBy("业主");
        rows.save(row).block(WAIT);
        input.setRequestId("register-more");input.setVersion(row.getVersion());input.setContent("不得覆盖证件");input.setWorkers(List.of());
        row=permits.registerWorkers(row.getId(),input).contextWrite(auth("admin")).block(WAIT);
        assertEquals("室内装修",row.getPermit().getContent());assertEquals("室内装修",row.getDetails().getWorkerRegistration().getContent());
        assertTrue(row.getPermit().getWorkers().isEmpty());assertEquals(collected,row.getPermit().getCollectedTime());
        assertEquals("frozen",row.getPermit().getPrints().get(0).snapshotFileId());
        rows.createUpdate().set("paymentStatus",0).where("id",row.getId()).execute().block(WAIT);
        input.setVersion(row.getVersion());input.setRequestId("unpaid-registration");
        assertCode(permits.registerWorkers(row.getId(),input),"payment_required","admin");
    }
    @Test void approvedReviewCannotBeRepeated() {
        var row=createSigned(create("approved-review"),false).contextWrite(auth("admin")).block(WAIT);
        row=approveSelf(row);
        var review=new DecorationRequests.Review();review.setRequestId("approve-again");review.setVersion(row.getVersion());review.setItemId("self-inspection");review.setResult(1);
        assertCode(reviews.review(row.getId(),review),"review_already_approved","admin");
        assertEquals(row.getVersion(),rows.findById(row.getId()).block(WAIT).getVersion());
    }
    @Test void inspectionWaitsForCollectionAndExtensionWaitsForExpiryWindow() {
        var row=ready();row.getPermit().setPrintTime(null);rows.save(row).block(WAIT);
        var inspection=inspection(row,System.currentTimeMillis(),System.currentTimeMillis()+86400000L);
        assertCode(workflow.assignInspection(row.getId(),inspection),"permit_required","admin");
        assertEquals(0,tasks.createQuery().count().block(WAIT));
        row.getPermit().setEndTime(System.currentTimeMillis()+10*86400000L);rows.save(row).block(WAIT);
        var extend=new DecorationRequests.ExtendPermit();extend.setRequestId("too-early-extension");extend.setVersion(row.getVersion());extend.setMonths(1);
        assertCode(permits.extend(row.getId(),extend),"permit_extension_too_early","admin");
    }
    private DecorationApplicationEntity ready(){var row=createSigned(create(UUID.randomUUID().toString()),false).contextWrite(auth("admin")).block(WAIT);row.getDetails().getSelfInspectionReview().setReviewStatus(1);row.setPaymentStatus(2);row.setPaidAmount(new BigDecimal("1000"));row.setDepositReviewStatus(1);var permit=new DecorationModels.Permit();permit.setPrintTime(System.currentTimeMillis());row.setPermit(permit);row.setStatus("permit_issued");rows.save(row).block(WAIT);return row;}
    private DecorationTaskEntity scheduledInspection(DecorationApplicationEntity row,String id,String status) {
        var task=new DecorationTaskEntity();task.setId(id);task.setApplicationId(row.getApplicationId());task.setTenantId(row.getTenantId());
        task.setProjectId(row.getProjectId());task.setProjectName(row.getProjectName());task.setDecorationId(row.getId());
        task.setNumber(row.getNumber());task.setSpaceName(row.getSpaceName());task.setRound(0);task.setKind("daily");task.setStatus(status);
        task.setVersion(1L);task.setCreateTime(System.currentTimeMillis());task.setModifyTime(task.getCreateTime());
        task.setDetails(new DecorationModels.TaskDetails());tasks.insert(task).block(WAIT);return task;
    }
    private void task(DecorationApplicationEntity row,String id,int round,String kind,String assignee,String status){var t=new DecorationTaskEntity();t.setId(id);t.setApplicationId("app");t.setTenantId("tenant");t.setProjectId("project");t.setDecorationId(row.getId());t.setRound(round);t.setKind(kind);t.setAssigneeId(assignee);t.setStatus(status);t.setVersion(1L);t.setCreateTime(System.currentTimeMillis());t.setModifyTime(t.getCreateTime());t.setDetails(new DecorationModels.TaskDetails());tasks.insert(t).block(WAIT);}
    private DecorationRequests.Feedback feedback(DecorationApplicationEntity row,String task){var f=new DecorationRequests.Feedback();f.setRequestId(UUID.randomUUID().toString());f.setVersion(row.getVersion());f.setTaskId(task);f.setPassed(true);return f;}
    private DecorationRequests.Rectification rectify(DecorationApplicationEntity row,String task){var f=new DecorationRequests.Rectification();f.setRequestId(UUID.randomUUID().toString());f.setVersion(row.getVersion());f.setTaskId(task);return f;}
    private void file(String id,String creator,String project,String app){var f=new DecorationAttachmentEntity();f.setId(id);f.setFileId(id);f.setName(id+".png");f.setContentType("image/png");f.setSize(1L);f.setApplicationId("app");f.setTenantId("tenant");f.setProjectId(project);f.setCreatorId(creator);f.setPurpose("draft");f.setDecorationId(app);f.setCreateTime(System.currentTimeMillis());files.insert(f).block(WAIT);}
    private DecorationRequests.Action action(DecorationApplicationEntity row){var action=new DecorationRequests.Action();action.setRequestId(UUID.randomUUID().toString());action.setVersion(row.getVersion());return action;}
    /** 其他流程测试使用真实签署请求结构，附件也经过相同的归属校验和消费事务。 */
    private Mono<DecorationApplicationEntity> createSigned(DecorationRequests.Create input, boolean mine) {
        return Authentication.currentReactive().flatMap(auth -> {
            var home=new PropertyHouseholdInfo("project","house","一栋101","residential","customer","owner","业主","13800000000","relationship","owner");
            var documents=new java.util.ArrayList<DecorationRequests.SignedDocument>();
            for (String kind:DecorationDocuments.TITLES.keySet()) {
                var document=new DecorationRequests.SignedDocument();document.setKind(kind);
                document.setContent(DecorationDocuments.content(kind,input,home,config("agreement").getContent().getAgreement()));
                String prefix=DecorationApplicationService.identity(input.getRequestId()+":"+auth.getUser().getId()+":"+kind);
                document.setSignatureFileId(prefix+":sign");document.setDocumentFileId(prefix+":document");documents.add(document);
            }
            if(mine)input.setSignedDocuments(documents);
            else {input.setSignedDocuments(List.of());input.setSelfInspectionFiles(List.of(documents.get(0).getDocumentFileId()));}
            return applications.create(input,mine);
        });
    }
    @Test void reviewAnnotationsKeepSourceAndMarksAndRejectForeignSource() {
        var config=config("materials");config.setVersion(1L);
        var definition=new DecorationModels.MaterialDefinition();definition.setId("drawing");definition.setName("装修图纸");definition.setKind("drawing");definition.setSource("upload");definition.setEnabled(true);definition.setRequired(true);
        config.getContent().setMaterials(new java.util.ArrayList<>(List.of(definition)));configurations.save(config).contextWrite(auth("admin")).block(WAIT);
        file("original-image","owner","project",null);file("annotated-image","admin","project",null);
        var input=create("annotation");var upload=new DecorationRequests.MaterialUpload();upload.setItemId("drawing");upload.setFileIds(List.of("original-image"));input.setMaterials(List.of(upload));
        var row=createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        var review=new DecorationRequests.Review();review.setRequestId("annotation-review");review.setVersion(row.getVersion());review.setItemId("drawing");review.setResult(2);review.setRemark("按框选位置补充");review.setAnnotatedFileId("annotated-image");review.setAnnotationSourceFileId("foreign-source");review.setAnnotations(List.of(new DecorationModels.ImageMark(.1,.1,.5,.4,"墙面")));
        assertCode(reviews.review(row.getId(),review),"invalid_annotations","admin");
        review.setAnnotationSourceFileId("original-image");row=reviews.review(row.getId(),review).contextWrite(auth("admin")).block(WAIT);
        var material=row.getDetails().getMaterials().get(0);assertEquals("original-image",material.getAnnotationSourceFileId());assertEquals("墙面",material.getAnnotations().get(0).text());assertEquals("annotated-image",material.getAnnotatedFileId());
        var ownerView=applications.read(row.getId(),true).contextWrite(auth("owner")).block(WAIT);assertEquals("annotated-image",ownerView.getDetails().getMaterials().get(0).getAnnotatedFileId());
        review.setRequestId("invalid-region");review.setVersion(row.getVersion());review.setAnnotations(List.of(new DecorationModels.ImageMark(.9,.1,.5,.4,"越界")));
        assertCode(reviews.review(row.getId(),review),"invalid_annotations","admin");
    }
    @Test void documentsAreRequiredAndChangedContentCannotReuseSignatures() {
        var input=create("unsigned");assertCode(applications.create(input,true),"signed_documents_required","owner");
        var row=createSigned(input,true).contextWrite(auth("owner")).block(WAIT);
        assertEquals(1,row.getDetails().getSignedDocuments().size());
        assertEquals("owner",row.getDetails().getSignedDocuments().get(0).getSignedBy());
        input.setRequestId("edited-signed");input.getSelfInspectionItems().get(0).setValue(false);
        assertCode(applications.create(input,true),"invalid_signed_document","owner");
    }
    private DecorationRequests.Create create(String key){var c=new DecorationRequests.Create();c.setRequestId(key);c.setProjectId("project");c.setSpaceId("house");c.setCustomerId("customer");c.setContactPhone("13800000000");c.setConstructionUnit("施工单位");c.setConstructionManager("负责人");c.setManagerPhone("13900000000");c.setOwnerCertificate("11010519491231002X");c.setManagerCertificate("11010519491231002X");c.setConstructionType(1);c.setDecorationType("first");c.setStartTime(System.currentTimeMillis());c.setCompletionTime(c.getStartTime()+86400000);c.setNoticesAccepted(true);
        c.setSelfInspectionItems(java.util.stream.Stream.of("phoneLine","cableTvLine","broadbandLine","videoIntercom","doorMagnet","other","kitchenDrainage","masterBathroomDrainage","balconyDrainage").map(keyName->{var item=new DecorationModels.SelfInspectionItem();item.setKey(keyName);item.setValue(true);return item;}).toList());
        for(String creator:List.of("owner","admin"))for(String kind:DecorationDocuments.TITLES.keySet()){
            String prefix=DecorationApplicationService.identity(key+":"+creator+":"+kind);
            for(String id:List.of(prefix+":sign",prefix+":document"))if(files.findById(id).block(WAIT)==null)file(id,creator,"project",null);
        }
        return c;}
    private DecorationRequests.Config config(String kind){var c=new DecorationRequests.Config();c.setProjectId("project");c.setKind(kind);c.setHouseType(Set.of("agreement","materials","checks").contains(kind)?"residential":"");c.setDecorationType(Set.of("materials","checks").contains(kind)?"first":"");c.setScene("checks".equals(kind)?"daily":"");var content=new DecorationModels.Configuration();var agreement=new DecorationModels.Agreement();agreement.setTitle("装修协议");agreement.setContent("装修须知");agreement.setChargeItemId("deposit");agreement.setChargeItemName("装修保证金");agreement.setDeposit(new BigDecimal("1000"));content.setAgreement(agreement);c.setContent(content);return c;}
    private PropertyOrganizationInfo project(String id){var p=new PropertyOrganizationInfo();p.setId(id);p.setName("项目");return p;}
    private DecorationApplicationEntity cloneRow(DecorationApplicationEntity row){return new ObjectMapper().convertValue(row,DecorationApplicationEntity.class);}
    private static reactor.util.context.Context auth(String id,String... roles){var user=new SimpleUser();user.setId(id);user.setName(id);var authentication=new SimpleAuthentication();authentication.setUser(user);authentication.setDimensions(Arrays.stream(roles).map(role->(org.hswebframework.web.authorization.Dimension) org.hswebframework.web.authorization.simple.SimpleDimension.of(role,role,new org.hswebframework.web.authorization.DimensionType(){public String getId(){return "role";}public String getName(){return "角色";}},Map.of())).collect(java.util.stream.Collectors.toList()));return reactor.util.context.Context.of(Authentication.class,authentication);}
    private void assertCode(Mono<?> publisher,String code,String actor){StepVerifier.create(publisher.contextWrite(auth(actor))).expectErrorMatches(e->e.getMessage()!=null&&e.getMessage().contains("error.decoration."+code)).verify(WAIT);}

    @SpringBootConfiguration @EnableTransactionManagement(proxyTargetClass=true)
    @EnableEasyormRepository("org.jetlinks.pro.property.decoration.entity")
    @Import({DecorationNotificationRepository.class,DecorationNotificationService.class,DecorationApplicationRepository.class,DecorationConfigurationRepository.class,DecorationHistoryRepository.class,DecorationTaskRepository.class,DecorationAttachmentRepository.class,DecorationFinanceReceiptRepository.class,
        DecorationOwnerHouseService.class,DecorationListStatusService.class,DecorationApplicationService.class,DecorationReviewListService.class,DecorationAcceptanceListService.class,DecorationInspectionListService.class,DecorationConfigurationService.class,DecorationReviewService.class,DecorationPermitService.class,DecorationPermitTermBuilder.class, DecorationTaskRoleTermBuilder.class,DecorationTaskService.class,DecorationAttachmentService.class,DecorationFinanceService.class,
        DecorationSelfInspectionRepository.class,DecorationSelfInspectionService.class,DecorationOverviewService.class,DecorationDashboardService.class,DecorationOverviewMetricTermBuilder.class,DecorationInspectionGenerator.class,DecorationPermitDocumentService.class,
        DecorationConfigurationImportRepository.class,DecorationConfigurationImportWriter.class,DecorationConfigurationImportService.class})
    @ImportAutoConfiguration({EasyormConfiguration.class,R2dbcSqlExecutorConfiguration.class,R2dbcAutoConfiguration.class,R2dbcTransactionManagerAutoConfiguration.class,TransactionAutoConfiguration.class,EntityFactoryConfiguration.class})
    static class Config {
        @Bean org.jetlinks.pro.property.decoration.integration.DecorationInsideMailSender mailSender(){return mock(org.jetlinks.pro.property.decoration.integration.DecorationInsideMailSender.class);}
        @Bean DecorationNotificationRecipients noticeRecipients(){return mock(DecorationNotificationRecipients.class);}
        @Bean DecorationScope scope(){return mock(DecorationScope.class);}
        @Bean DecorationAssigneeService assignees(){return mock(DecorationAssigneeService.class);}
        @Bean DecorationRoleService roles(){return mock(DecorationRoleService.class);}
        @Bean DecorationFileStorage fileStorage(){return mock(DecorationFileStorage.class);}
        @Bean DecorationProjectSealGateway projectSeals(){return mock(DecorationProjectSealGateway.class);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
    }
}
