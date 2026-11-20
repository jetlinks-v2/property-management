package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** PostgreSQL真实持久化、事务和CAS；隔离资产授权和绑定，分别由Scope与Ownership契约测试覆盖。 */
@SpringBootTest(
  classes = FacilityPersistenceTest.Config.class,
  webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class FacilityPersistenceTest {

  static final GenericContainer<?> DB = new GenericContainer<>(
    "postgres:16-alpine"
  )
    .withEnv("POSTGRES_PASSWORD", "test-password")
    .withEnv("POSTGRES_DB", "facility_test")
    .withExposedPorts(5432);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    DB.start();
    r.add(
      "spring.r2dbc.url",
      () ->
        "r2dbc:postgresql://127.0.0.1:" +
        DB.getMappedPort(5432) +
        "/facility_test"
    );
    r.add("spring.r2dbc.username", () -> "postgres");
    r.add("spring.r2dbc.password", () -> "test-password");
    r.add("easyorm.dialect", () -> "postgres");
    r.add("easyorm.default-schema", () -> "public");
  }

  @Autowired
  TaskStore store;

  @Autowired
  FaultStore faults;

  @Autowired
  ProcessStore logs;

  @Autowired
  JobStore jobs;

  @Autowired
  FacilityQueries queries;

  @Autowired
  FacilityTasks manager;

  @org.springframework.boot.test.mock.mockito.SpyBean
  FacilityRecords records;

  @Autowired
  FacilityScope scope;

  @Autowired
  FacilityDirectory directory;

  @Autowired
  FacilityFiles files;

  @Autowired FacilityExecution faultExecution;
  @Autowired FacilityCloud cloud;
  @Autowired FacilitySaasAlarmWriter saasWriter;
  @Autowired FacilityEmployeeMessages employeeMessages;
  final Map<String, org.jetlinks.sdk.server.device.DeviceInfo> boundSources = new LinkedHashMap<>();

  void bindSource(String id) {
    var source = new org.jetlinks.sdk.server.device.DeviceInfo(); source.setId(id); boundSources.put(id, source);
  }

  @BeforeEach
  void resetData() {
    store.createDelete().where("id", "test").execute().block();
    faults
      .createDelete()
      .where("id", FacilityRecords.stableId("test", "inspection-fault"))
      .execute()
      .block();
    logs.createDelete().where("targetId", "test").execute().block();
    reset(scope, directory, files, notifier, cloud);
    boundSources.clear();
    when(cloud.snapshot(null)).thenAnswer(call -> Mono.fromSupplier(() -> List.copyOf(boundSources.values())));
    when(directory.projects()).thenReturn(Flux.just(project("p-a"), project("p-b")));
    when(scope.projectExists(anyString())).thenReturn(Mono.just(true));
    reset(faultExecution);
    when(faultExecution.asUser(any(), anyString(), any())).thenAnswer(call -> {
      String user = call.getArgument(1);
      java.util.function.Supplier<Mono<Object>> work = call.getArgument(2);
      var auth = mock(org.hswebframework.web.authorization.Authentication.class);
      when(auth.hasPermission(eq("facility-fault"), anyString())).thenAnswer(permission ->
        !user.equals("no-" + permission.<String>getArgument(1)));
      return Mono.defer(work).contextWrite(c -> c.put(org.hswebframework.web.authorization.Authentication.class, auth));
    });
    when(notifier.enqueue(any(), any(), any())).thenReturn(Mono.empty());
    when(notifier.enqueueTarget(any(), any(), any())).thenReturn(Mono.empty());
    when(directory.taskAssignee(any(), anyString())).thenReturn(Mono.empty());
    when(directory.employeeInfo(anyString())).thenReturn(Mono.empty());
    when(directory.projectEmployee(anyString(), anyString())).thenAnswer(call -> Mono.just(
      new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo(call.getArgument(1), "Employee", null, List.of(call.<String>getArgument(0)))));
    when(scope.current()).thenReturn(
      Mono.deferContextual(c -> Mono.just(c.get(FacilityContext.class)))
    );
    when(scope.applicationQuery(any())).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      return scope.current().map(context -> FacilityScope.applicationQuery(query, context));
    });
    when(scope.assertRecord(any(), any(), any(), any())).thenReturn(
      Mono.empty()
    );
    when(directory.employee(anyString())).thenReturn(
      Mono.just(new PropertyUserInfo())
    );
    when(files.validate(anyCollection(), anyString())).thenReturn(Mono.empty());
    when(scope.query(any(), any(), anyString())).thenAnswer(call ->
      Mono.just(call.getArgument(0))
    );
  }

  <T> Mono<T> as(String user, Mono<T> work) {
    return work.contextWrite(c ->
      c.put(
        FacilityContext.class,
        new FacilityContext("tenant", "app", user, user)
      )
    );
  }

  TaskEntity task(String mode) {
    var row = FacilityScope.initialize(
      new TaskEntity(),
      new FacilityContext("tenant", "app", "admin", "管理员")
    );
    row.setId("test");
    row.setProjectId("p");
    row.setOrganizationId("p");
    row.setDeviceId("device");
    row.setName("水泵巡检");
    row.setDeviceName("泵1");
    row.setKind("inspection");
    row.setMode(mode);
    row.setExternalOrganizationId("external-org");
    row.setState("open");
    row.setReviewerId("reviewer");
    row.setFollowUserId("follower");
    row.setDeadline(System.currentTimeMillis() + 100000);
    var check = new CheckItem();
    check.setId("check");
    check.setName("状态");
    check.setKind("boolean");
    check.setRequired(true);
    row.setStandardSnapshot(List.of(check));
    store.insert(row).block();
    return row;
  }

  TaskCommand command(long version) {
    var c = new TaskCommand();
    c.setVersion(version);
    return c;
  }

  TaskCommand submit(long version, String key) {
    var c = command(version);
    c.setSubmissionId(key);
    var r = new CheckResult();
    r.setItemId("check");
    r.setValue("true");
    r.setAbnormal(false);
    r.setAttachments(List.of());
    c.setResults(List.of(r));
    return c;
  }

  private static final List<String> PROJECT_QUERY_IDS = java.util.stream.IntStream.range(0, 7)
    .mapToObj(i -> "project-query-" + i).toList();

  private void projectQueryFixtures() {
    jobs.createDelete().in("id", PROJECT_QUERY_IDS).execute().block();
    logs.createDelete().in("id", PROJECT_QUERY_IDS).execute().block();
    for (int i = 0; i < PROJECT_QUERY_IDS.size(); i++) {
      var context = new FacilityContext(i == 4 ? "other-tenant" : "tenant",
        i == 5 ? "other-app" : "app", "admin", "管理员");
      String project = i == 2 ? "p-b" : i == 3 ? "p-hidden" : "p-a";
      var job = FacilityScope.initialize(new JobEntity(), context);
      job.setId(PROJECT_QUERY_IDS.get(i));
      job.setProjectId(project);
      job.setKind(job.getId());
      job.setDeleted(i == 6);
      jobs.insert(job).block();
      var log = FacilityScope.initialize(new ProcessEntity(), context);
      log.setId(job.getId());
      log.setProjectId(project);
      log.setTargetId("project-query");
      log.setDeleted(i == 6);
      logs.insert(log).block();
    }
  }

  private PropertyOrganizationInfo project(String id) {
    var project = new PropertyOrganizationInfo();
    project.setId(id);
    return project;
  }

  private QueryParamEntity projectQuery() {
    return QueryParamEntity.newQuery().in("id", PROJECT_QUERY_IDS).orderByAsc("id").getParam();
  }

  @Test
  void remoteAuthorizedProjectsIntersectLocalTenantApplicationAndDeletedBeforePaging() {
    projectQueryFixtures();
    when(directory.projects()).thenReturn(Flux.just(project("p-a"), project("p-b"), project("p-a")));
    var query = projectQuery();
    query.setPageSize(1);
    query.setPageIndex(1);
    var jobPage = as("admin", queries.projects(jobs, query)).block();
    var logPage = as("admin", queries.projects(logs, query)).block();
    assertEquals(3, jobPage.getTotal());
    assertEquals(3, logPage.getTotal());
    assertEquals(List.of("project-query-1"), jobPage.getData().stream().map(JobEntity::getId).toList());
    assertEquals(List.of("project-query-1"), logPage.getData().stream().map(ProcessEntity::getId).toList());
    // Caller-supplied filters remain unmodified and cannot widen the trusted directory result.
    when(directory.projects()).thenReturn(Flux.just(project("p-b")));
    var limited = as("admin", queries.projects(jobs, projectQuery())).block();
    assertEquals(1, limited.getTotal());
    assertEquals("project-query-2", limited.getData().get(0).getId());
    var hidden = QueryParamEntity.newQuery().where("id", "project-query-3").getParam();
    assertEquals(0, as("admin", queries.projects(logs, hidden)).block().getTotal());
  }

  @Test
  void callerOrConditionsCannotEscapeProjectQueryScope() {
    projectQueryFixtures();
    when(directory.projects()).thenReturn(Flux.just(project("p-a")));
    var query = QueryParamEntity.newQuery().in("id", PROJECT_QUERY_IDS)
      .or("tenantId", "other-tenant").or("applicationId", "other-app")
      .or("deleted", true).getParam();
    var page = as("admin", queries.projects(logs, query)).block();
    assertEquals(2, page.getTotal());
    assertEquals(Set.of("project-query-0", "project-query-1"),
      new HashSet<>(page.getData().stream().map(ProcessEntity::getId).toList()));
  }

  @Test
  void emptyProjectAuthorizationReturnsNoJobsOrOperations() {
    projectQueryFixtures();
    when(directory.projects()).thenReturn(Flux.empty());
    assertEquals(0, as("admin", queries.projects(jobs, projectQuery())).block().getTotal());
    assertTrue(as("admin", queries.projects(logs, projectQuery())).block().getData().isEmpty());
  }

  @Test
  void directoryFailureDoesNotFallBackToApplicationWideRecords() {
    projectQueryFixtures();
    when(directory.projects()).thenReturn(Flux.error(new IllegalStateException("directory unavailable")));
    StepVerifier.create(as("admin", queries.projects(jobs, projectQuery())))
      .expectErrorMessage("directory unavailable").verify();
    StepVerifier.create(as("admin", queries.projects(logs, projectQuery())))
      .expectErrorMessage("directory unavailable").verify();
  }

  @Test
  void concurrentClaimHasExactlyOneWinner() {
    task("single");
    var result = Flux.merge(
      as("worker-a", manager.action("test", "claim", command(0)))
        .map(v -> "success")
        .onErrorReturn("conflict"),
      as("worker-b", manager.action("test", "claim", command(0)))
        .map(v -> "success")
        .onErrorReturn("conflict")
    )
      .collectList()
      .block(Duration.ofSeconds(10));
    assertEquals(1, result.stream().filter("success"::equals).count());
    assertEquals(1, result.stream().filter("conflict"::equals).count());
    assertEquals("claimed", store.findById("test").block().getState());
    assertEquals(1L, store.findById("test").block().getVersion());
  }

  @Test
  void singleTaskReplayAndAuditDoNotDuplicate() {
    task("single");
    as("worker", manager.action("test", "claim", command(0))).block();
    as(
      "worker",
      manager.action("test", "submit", submit(1, "offline-key"))
    ).block();
    var replay = as(
      "worker",
      manager.action("test", "submit", submit(1, "offline-key"))
    ).block();
    assertEquals("review", replay.getState());
    assertEquals(2L, replay.getVersion());
    assertEquals(
      2,
      logs.createQuery().where("targetId", "test").count().block()
    );
    var done = as(
      "reviewer",
      manager.action("test", "approve", command(2))
    ).block();
    assertEquals("completed", done.getState());
    assertNotNull(done.getCompletedTime());
  }

  @Test
  void legacyDoubleTaskNowSubmitsDirectlyToResponsiblePersonReview() {
    task("double");
    as("worker", manager.action("test", "claim", command(0))).block();
    var submitted = as("worker", manager.action("test", "submit", submit(1, "legacy-key"))).block();
    assertEquals("review", submitted.getState());
    assertEquals("completed", as("reviewer", manager.action("test", "approve", command(2))).block().getState());
  }

  @Test
  void previouslySubmittedExternalTaskCanBeReviewedWithoutFollowUp() {
    var row = task("double"); row.setState("external-submitted"); store.save(row).block();
    assertEquals("completed", as("reviewer", manager.action("test", "approve", command(0))).block().getState());
  }

  @Test
  void reviewTabsIntersectCurrentResponsiblePersonAndAssetScopeBeforePaging() {
    var states = List.of("review", "external-submitted", "completed", "rectify", "open", "review", "claimed");
    var ids = new ArrayList<String>();
    for (int i = 0; i < states.size(); i++) {
      var row = FacilityScope.initialize(new TaskEntity(), new FacilityContext("tenant", "app", "admin", "Admin"));
      row.setId("approval-fixture-" + i); row.setName("approval-fixture"); row.setKind("inspection");
      row.setState(states.get(i)); row.setReviewerId(i == 5 ? "someone-else" : "approval-reviewer");
      row.setProjectId("p"); store.save(row).block(); ids.add(row.getId());
    }
    try {
      var query = QueryParamEntity.newQuery().in("id", ids).or("reviewerId", "someone-else")
        .or("state", "completed").orderByAsc("id").getParam();
      query.setPageSize(1);
      var pending = as("approval-reviewer", manager.reviews(query, "pending")).block();
      assertEquals(2, pending.getTotal());
      assertEquals("approval-fixture-0", pending.getData().get(0).getId());
      query.setPageIndex(1);
      assertEquals("approval-fixture-1", as("approval-reviewer", manager.reviews(query, "pending")).block().getData().get(0).getId());
      query.setPageIndex(0); query.setPageSize(12);
      assertEquals(List.of("approval-fixture-2"), as("approval-reviewer", manager.reviews(query, "approved")).block()
        .getData().stream().map(TaskEntity::getId).toList());
      assertEquals(List.of("approval-fixture-3"), as("approval-reviewer", manager.reviews(query, "rejected")).block()
        .getData().stream().map(TaskEntity::getId).toList());
      assertEquals(4, as("approval-reviewer", manager.reviews(query, "all")).block().getTotal());
      doAnswer(call -> Mono.just(((QueryParamEntity) call.getArgument(0))
        .toNestQuery().and("id", "approval-fixture-1").getParam())).when(scope).query(any(), any(), anyString());
      assertEquals(1, as("approval-reviewer", manager.reviews(query, "all")).block().getTotal());
      doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException())).when(scope).query(any(), any(), anyString());
      StepVerifier.create(as("approval-reviewer", manager.reviews(query, "pending")))
        .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
      assertThrows(BusinessException.class, () -> manager.reviews(query, "unknown"));
    } finally { store.createDelete().in("id", ids).execute().block(); }
  }

  @Test
  void rejectedTaskReturnsToSameAssigneeForCorrectionAndResponsiblePersonCanReviewAgain() {
    task("single");
    as("worker", manager.action("test", "claim", command(0))).block();
    as("worker", manager.action("test", "submit", submit(1, "first"))).block();
    var reject = command(2); reject.setComment("补充现场照片");
    StepVerifier.create(as("other", manager.action("test", "reject", reject))).expectError(BusinessException.class).verify();
    StepVerifier.create(as("reviewer", manager.action("test", "reject", command(2)))).expectError(BusinessException.class).verify();
    var rejected = as("reviewer", manager.action("test", "reject", reject)).block();
    assertEquals("rectify", rejected.getState()); assertEquals("worker", rejected.getAssigneeId());
    assertEquals("补充现场照片", rejected.getReviewComment()); assertEquals(1, rejected.getResults().size());
    assertNull(store.findById("test").block().getSubmissionId());
    StepVerifier.create(as("other", manager.action("test", "submit", submit(3, "redo")))).expectError(BusinessException.class).verify();
    assertEquals("review", as("worker", manager.action("test", "submit", submit(3, "redo"))).block().getState());
    StepVerifier.create(as("reviewer", manager.action("test", "approve", command(2)))).expectError(BusinessException.class).verify();
    assertEquals("completed", as("reviewer", manager.action("test", "approve", command(4))).block().getState());
    assertEquals(5, logs.createQuery().where("targetId", "test").count().block());
  }

  @Test
  void closingTaskIsTerminalAndLateOfflineReplayCannotReopenIt() {
    for (var state : List.of("open", "claimed", "executing", "rectify", "review", "external-submitted")) {
      store.createDelete().where("id", "test").execute().block();
      logs.createDelete().where("targetId", "test").execute().block();
      var row = task("single"); row.setState(state); row.setAssigneeId("worker"); row.setSubmissionId("offline"); store.save(row).block();
      var closed = as("dispatcher", manager.action("test", "close", command(0))).block();
      assertEquals("closed", closed.getState()); assertNotNull(closed.getCompletedTime());
      for (String action : List.of("claim", "start", "submit", "transfer", "approve", "reject", "close", "cancel")) {
        var cmd = submit(1, "offline"); cmd.setUserId("next"); cmd.setComment("重做");
        StepVerifier.create(as(action.equals("approve") || action.equals("reject") ? "reviewer" : "worker",
          manager.action("test", action, cmd))).expectError(BusinessException.class).verify();
      }
      assertEquals("closed", store.findById("test").block().getState());
      assertEquals(1, logs.createQuery().where("targetId", "test").count().block());
    }
  }

  @Test
  void completedOrUnauthorizedTaskCannotBeClosed() {
    var row = task("single"); row.setState("completed"); store.save(row).block();
    StepVerifier.create(as("dispatcher", manager.action("test", "close", command(0)))).expectError(BusinessException.class).verify();
    row.setState("open"); store.save(row).block();
    when(scope.assertRecord(any(), any(), any(), any()))
      .thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    StepVerifier.create(as("dispatcher", manager.action("test", "close", command(0))))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    assertEquals("open", store.findById("test").block().getState());
    assertEquals(0, logs.createQuery().where("targetId", "test").count().block());
  }

  @Test
  void transferRequiresNewProjectEmployeeAndClearsPreviousExecutionAndReviewDraft() {
    task("single");
    as("worker", manager.action("test", "claim", command(0))).block();
    as("worker", manager.action("test", "submit", submit(1, "first"))).block();
    var reject = command(2); reject.setComment("需要重做");
    as("reviewer", manager.action("test", "reject", reject)).block();
    var transfer = command(3); transfer.setUserId("worker");
    StepVerifier.create(as("dispatcher", manager.action("test", "transfer", transfer))).expectError(BusinessException.class).verify();
    transfer.setUserId("next"); as("dispatcher", manager.action("test", "transfer", transfer)).block();
    var next = store.findById("test").block();
    assertEquals("next", next.getAssigneeId()); assertEquals("claimed", next.getState());
    assertTrue(next.getResults().isEmpty()); assertNull(next.getReviewComment()); assertNull(next.getSubmissionId());
    StepVerifier.create(as("worker", manager.action("test", "submit", submit(4, "first")))).expectError(BusinessException.class).verify();
    assertEquals("review", as("next", manager.action("test", "submit", submit(4, "next-submit"))).block().getState());
  }

  @Test
  void projectPoolFiltersBeforePagingAndCallerOrCannotEscapeMembership() {
    for (int i = 0; i < 5; i++) {
      var row = FacilityScope.initialize(new TaskEntity(), new FacilityContext("tenant", "app", "creator", "Creator"));
      row.setId("pool-fixture-" + i); row.setName("pool-fixture"); row.setMode(i == 1 ? "double" : null);
      row.setProjectId(i == 3 ? "other" : "p"); row.setState(i == 4 ? "completed" : "open");
      store.save(row).block();
    }
    var live = spy(new FacilityDirectory());
    var user = new org.hswebframework.web.authorization.simple.SimpleUser(); user.setId("worker");
    var auth = new org.hswebframework.web.authorization.simple.SimpleAuthentication(); auth.setUser(user);
    doReturn(Mono.just(new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo("worker", "Worker", null, List.of("p"))))
      .when(live).employeeInfo("worker");
    when(directory.claimPool(any())).thenAnswer(call -> live.claimPool(call.getArgument(0))
      .contextWrite(c -> c.put(org.hswebframework.web.authorization.Authentication.class, auth)));
    var query = QueryParamEntity.newQuery().is("name", "pool-fixture").or().is("projectId", "other").orderByAsc("id").getParam();
    query.setPageSize(2);
    var page = as("worker", manager.query(query, false, true)).block();
    assertEquals(3, page.getTotal()); assertEquals(2, page.getData().size());
    assertTrue(page.getData().stream().allMatch(t -> "p".equals(t.getProjectId()) && "open".equals(t.getState())));
    query.setPageIndex(1);
    assertEquals(1, as("worker", manager.query(query, false, true)).block().getData().size());
    doReturn(Mono.just(new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo("worker", "Worker", null, List.of())))
      .when(live).employeeInfo("worker");
    assertEquals(0, as("worker", manager.query(query, false, true)).block().getTotal());
    doReturn(Mono.empty()).when(live).employeeInfo("worker");
    assertEquals(0, as("worker", manager.query(query, false, true)).block().getTotal());
    store.createDelete().where("name", "pool-fixture").execute().block();
  }

  @Test
  void deniedDispatchAndRevokedMembershipNeverWriteOrLog() {
    task("double");
    when(directory.taskAssignee(any(), eq("outside"))).thenReturn(Mono.error(new BusinessException("outside project")));
    var transfer = command(0); transfer.setUserId("outside");
    StepVerifier.create(as("dispatcher", manager.action("test", "transfer", transfer))).expectError(BusinessException.class).verify();
    assertEquals("open", store.findById("test").block().getState());
    assertEquals(0, logs.createQuery().where("targetId", "test").count().block());
    transfer.setUserId("worker");
    as("dispatcher", manager.action("test", "transfer", transfer)).block();
    when(directory.taskAssignee(any(), eq("worker"))).thenReturn(Mono.error(new BusinessException("membership revoked")));
    StepVerifier.create(as("worker", manager.action("test", "submit", submit(1, "revoked"))))
      .expectError(BusinessException.class).verify();
    assertEquals("claimed", store.findById("test").block().getState());
    assertEquals(1L, store.findById("test").block().getVersion());
  }

  @Test
  void offlineSubmitAfterTransferFailsAndPreservesServerAssignee() {
    task("single");
    as("worker", manager.action("test", "claim", command(0))).block();
    var transfer = command(1);
    transfer.setUserId("next");
    as("dispatcher", manager.action("test", "transfer", transfer)).block();
    StepVerifier.create(
      as("worker", manager.action("test", "submit", submit(1, "offline-key")))
    )
      .expectError(BusinessException.class)
      .verify();
    var row = store.findById("test").block();
    assertEquals("next", row.getAssigneeId());
    assertEquals("claimed", row.getState());
    assertNull(row.getSubmissionId());
  }

  @Autowired
  BindingStore bindings;

  @Autowired
  DeviceStore devices;

  @Autowired
  MappingStore mappings;

  @Test
  void collectionFailurePreservesCursorAndRecoveryClearsStatus() {
    var context = new FacilityContext("tenant", "app", "collector", "Collector");
    var mapping = FacilityScope.initialize(new MappingEntity(), context);
    mapping.setId("collection-status-mapping");
    mapping.setDeviceId("collection-status-device");
    mapping.setProjectId("p");
    mapping.setEnabled(true);
    var metric = new MetricMapping();
    metric.setPropertyId("total");
    metric.setKind("counter");
    mapping.setMetrics(List.of(metric));
    mappings.save(mapping).block();
    var device = FacilityScope.initialize(new DeviceEntity(), context);
    device.setId(mapping.getDeviceId());
    device.setCloudDeviceId("collection-status-cloud");
    device.setSyncState("active");
    device.setSourceState("online");
    device.setEnergyEnabled(true);
    var cloud = mock(FacilityCloud.class);
    var deviceAccess = mock(FacilityDevices.class);
    when(deviceAccess.read(device.getId())).thenReturn(Mono.just(device));
    when(deviceAccess.requireBound(device)).thenReturn(Mono.just(device));
    when(deviceAccess.isBound(device)).thenReturn(Mono.just(true));
    var execution = mock(FacilityExecution.class);
    when(execution.asOwnerFlux(any(), any())).thenReturn(Flux.never());
    var telemetry = new FacilityTelemetry(
      cloud, mock(FacilityReadings.class), deviceAccess, mappings, execution
    );
    try {
      when(cloud.history(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Flux.empty());
      StepVerifier.create(telemetry.collect(mapping)).verifyComplete();
      var success = mappings.findById(mapping.getId()).block();
      assertNotNull(success.getLastCollectionSuccessTime());
      assertNull(success.getCollectionError());
      when(cloud.history(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Flux.error(new IllegalStateException("private payload")));
      StepVerifier.create(telemetry.collect(success))
        .expectError(IllegalStateException.class).verify();
      var failed = mappings.findById(mapping.getId()).block();
      assertEquals(success.getLastCollectedTime(), failed.getLastCollectedTime());
      assertEquals(success.getLastCollectionSuccessTime(), failed.getLastCollectionSuccessTime());
      assertEquals("IllegalStateException", failed.getCollectionError());
      when(cloud.history(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(Flux.empty());
      StepVerifier.create(telemetry.collect(failed)).verifyComplete();
      var recovered = mappings.findById(mapping.getId()).block();
      assertNull(recovered.getCollectionError());
      assertTrue(recovered.getLastCollectionSuccessTime() >= failed.getLastCollectionTime());
      when(deviceAccess.isBound(device)).thenReturn(Mono.just(false));
      StepVerifier.create(telemetry.collectCurrent(recovered)).expectNext(false).verifyComplete();
      assertEquals(0, telemetry.activeSubscriptions());
      StepVerifier.create(telemetry.collect(recovered))
        .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
      assertEquals(recovered.getLastCollectedTime(), mappings.findById(mapping.getId()).block().getLastCollectedTime());
      assertTrue(mappings.findById(mapping.getId()).block().getEnabled());
      when(deviceAccess.isBound(device)).thenReturn(Mono.just(true));
      StepVerifier.create(telemetry.collect(recovered)).verifyComplete();
      assertEquals(1, telemetry.activeSubscriptions());
      mappings.createUpdate().set("version", recovered.getVersion() + 1)
        .set("collectionError", "CurrentConfigurationFailure")
        .where("id", mapping.getId()).execute().block();
      StepVerifier.create(telemetry.collect(recovered)).verifyComplete();
      assertEquals("CurrentConfigurationFailure", mappings.findById(mapping.getId()).block().getCollectionError());
    } finally {
      telemetry.stop();
    }
  }

  @Autowired
  ReadingStore readings;

  @Autowired
  NotificationStore notifications;

  @Autowired
  FacilityOwner owner;

  @Autowired
  AlertStore alerts;

  @Autowired
  FacilityAlertWriter alertWriter;

  @Autowired
  FacilityNotifications notifier;

  @Test
  void ownersOnlyReadOwnBindingIntervalWithoutDevicePermissions() {
    var device = FacilityScope.initialize(
      new DeviceEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    device.setId("owner-device");
    device.setProjectId("p");
    device.setName("住宅水表");
    devices.save(device).block();
    var mapping = FacilityScope.initialize(
      new MappingEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    mapping.setId("owner-mapping");
    mapping.setDeviceId(device.getId());
    mapping.setProjectId("p");
    mapping.setEnergyType("water");
    mapping.setStaleAfterSeconds(100L);
    var metric = new MetricMapping();
    metric.setPropertyId("water");
    metric.setKind("counter");
    metric.setUnit("m³");
    metric.setName("用水量");
    mapping.setMetrics(List.of(metric));
    mappings.save(mapping).block();
    for (int i = 1; i <= 3; i++) {
      var row = FacilityScope.initialize(
        new ReadingEntity(),
        new FacilityContext("tenant", "app", "staff", "Staff")
      );
      row.setId("owner-reading-" + i);
      row.setDeviceId(device.getId());
      row.setProjectId("p");
      row.setPropertyId("water");
      row.setMetricKind("counter");
      row.setUnit("m³");
      row.setStatus("accepted");
      row.setValue(java.math.BigDecimal.valueOf(i * 10));
      row.setObservedTime(i * 1000L);
      row.setMappingVersion(0L);
      row.setMeterEpoch(0L);
      readings.save(row).block();
    }
    for (String user : List.of("alice", "bob")) {
      var binding = FacilityScope.initialize(
        new BindingEntity(),
        new FacilityContext("tenant", "app", "staff", "Staff")
      );
      binding.setId("owner-binding-" + user);
      binding.setDeviceId(device.getId());
      binding.setProjectId("p");
      binding.setUserId(user);
      binding.setStartTime(user.equals("alice") ? 1000L : 2000L);
      binding.setEndTime(user.equals("alice") ? 2000L : null);
      bindings.save(binding).block();
    }
    StepVerifier.create(as("bob", owner.binding("owner-binding-alice")))
      .expectError(
        org.hswebframework.web.authorization.exception.AccessDenyException.class
      )
      .verify();
    var detail = as("bob", owner.detail("owner-binding-bob", 0, 4000)).block();
    var views = (List<Map<String, Object>>) detail.get("metrics");
    var samples = (List<Map<String, Object>>) views.get(0).get("readings");
    assertEquals(
      List.of(2000L, 3000L),
      samples
        .stream()
        .map(row -> row.get("time"))
        .toList()
    );
    assertFalse(detail.containsKey("customerName"));
    var old = as("alice", owner.detail("owner-binding-alice", 0, 4000)).block();
    var oldMetrics = (List<Map<String, Object>>) old.get("metrics");
    assertEquals(1, ((List<?>) oldMetrics.get(0).get("readings")).size());
  }

  @Test
  void alertEpisodeEscalationRecoveryAndOutboxAreAtomic() {
    String id = FacilityRecords.stableId("alert-rule", "alert-device");
    alerts.createDelete().where("id", id).execute().block();
    reset(notifier);
    when(notifier.enqueue(any(), any(), any())).thenReturn(Mono.empty());
    var rule = FacilityScope.initialize(
      new RuleEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    rule.setId("alert-rule");
    rule.setName("日用量");
    rule.setRuleType("usage");
    rule.setThreshold(new java.math.BigDecimal("100"));
    rule.setNearRatio(new java.math.BigDecimal("0.8"));
    rule.setRepeatHours(24);
    var device = FacilityScope.initialize(
      new DeviceEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    device.setId("alert-device");
    device.setProjectId("p");
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        new java.math.BigDecimal("80"),
        "kWh",
        1000,
        "day"
      )
    ).block();
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        new java.math.BigDecimal("85"),
        "kWh",
        2000,
        "day"
      )
    ).block();
    assertEquals(1, alerts.findById(id).block().getNotificationSequence());
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        new java.math.BigDecimal("110"),
        "kWh",
        3000,
        "day"
      )
    ).block();
    assertEquals(2, alerts.findById(id).block().getNotificationSequence());
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        new java.math.BigDecimal("60"),
        "kWh",
        4000,
        "day"
      )
    ).block();
    assertEquals("recovered", alerts.findById(id).block().getState());
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        new java.math.BigDecimal("90"),
        "kWh",
        5000,
        "day"
      )
    ).block();
    assertEquals(3, alerts.findById(id).block().getNotificationSequence());
    verify(notifier, times(3)).enqueue(any(), any(), any());
    when(notifier.enqueue(any(), any(), any())).thenReturn(
      Mono.error(new IllegalStateException("outbox unavailable"))
    );
    StepVerifier.create(
      as(
        "staff",
        alertWriter.record(
          rule,
          device,
          new java.math.BigDecimal("120"),
          "kWh",
          6000,
          "day"
        )
      )
    )
      .expectError(IllegalStateException.class)
      .verify();
    assertEquals(3, alerts.findById(id).block().getNotificationSequence());
    assertEquals("near", alerts.findById(id).block().getLevel());
  }

  @Test
  void signedReceiptRequiresMatchingProviderRecipientAndAcceptedMessage() {
    var row = FacilityScope.initialize(
      new NotificationEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    row.setId("receipt-test");
    row.setState("submitted");
    row.setNotifierId("notifier");
    row.setProviderMessageId("message");
    row.setTelephone("13800000000");
    row.setChannel("sms");
    notifications.save(row).block();
    String secret = "0123456789abcdef0123456789abcdef";
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var controller =
      new org.jetlinks.pro.property.facility.web.FacilityReceiptController(
        notifications,
        mapper,
        secret
      );
    long time = System.currentTimeMillis();
    String body =
      "{\"notificationId\":\"receipt-test\",\"notifierId\":\"notifier\",\"sendId\":\"message\",\"receiver\":\"13800000000\",\"delivered\":true}";
    StepVerifier.create(controller.receipt(time, "00", body))
      .expectError(BusinessException.class)
      .verify();
    assertEquals(
      "submitted",
      notifications.findById(row.getId()).block().getState()
    );
    controller.receipt(time, signature(secret, time, body), body).block();
    assertEquals(
      "delivered",
      notifications.findById(row.getId()).block().getState()
    );
    assertNotNull(
      notifications.findById(row.getId()).block().getDeliveredTime()
    );
  }

  private String signature(String secret, long timestamp, String body) {
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
        new javax.crypto.spec.SecretKeySpec(
          secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "HmacSHA256"
        )
      );
      return java.util.HexFormat.of().formatHex(
        mac.doFinal(
          (timestamp + "\n" + body).getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired
  FacilityDevices deviceManager;

  @Autowired
  FacilityBindings bindingManager;

  @Test
  void movingProjectEndsBindingsPausesMappingsAndKeepsHistory() {
    when(scope.projectExists("old-project")).thenReturn(Mono.just(false));
    doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()))
      .when(records).assertProject(eq("old-project"), any());
    var c = new FacilityContext("tenant", "app", "admin", "Admin");
    var device = FacilityScope.initialize(new DeviceEntity(), c);
    device.setId("moving-device");
    device.setProjectId("old-project");
    device.setOrganizationId("old-project");
    device.setCandidateProjectIds(List.of("new-project"));
    device.setPendingProjectId("new-project");
    device.setSyncState("pending-project");
    device.setSpaceId("old-house");
    device.setResponsibleUserId("old-worker");
    device.setInspectionEnabled(true);
    device.setMaintenanceEnabled(true);
    device.setEnergyEnabled(true);
    devices.save(device).block();
    var binding = FacilityScope.initialize(new BindingEntity(), c);
    binding.setId("moving-binding");
    binding.setProjectId("old-project");
    binding.setDeviceId(device.getId());
    binding.setStartTime(1L);
    binding.setUserId("old-owner");
    bindings.save(binding).block();
    var mapping = FacilityScope.initialize(new MappingEntity(), c);
    mapping.setId("moving-mapping");
    mapping.setProjectId("old-project");
    mapping.setDeviceId(device.getId());
    mapping.setEnabled(true);
    mappings.save(mapping).block();
    var project =
      new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
    project.setId("new-project");
    project.setName("New project");
    when(directory.project("new-project")).thenReturn(Mono.just(project));
    doReturn(Mono.empty())
      .when(records)
      .assertProject(eq("new-project"), any());
    as(
      "admin",
      deviceManager.confirmProject(device.getId(), "new-project", 0)
    ).block();
    var saved = devices.findById(device.getId()).block();
    assertEquals("new-project", saved.getProjectId());
    assertEquals("active", saved.getSyncState());
    assertNull(saved.getSpaceId());
    assertNull(saved.getPendingProjectId());
    assertNull(saved.getResponsibleUserId());
    assertFalse(saved.getInspectionEnabled());
    assertFalse(saved.getMaintenanceEnabled());
    assertFalse(saved.getEnergyEnabled());
    assertNotNull(bindings.findById(binding.getId()).block().getEndTime());
    assertEquals(
      "old-project",
      bindings.findById(binding.getId()).block().getProjectId()
    );
    assertFalse(mappings.findById(mapping.getId()).block().getEnabled());
    assertEquals(
      "old-project",
      mappings.findById(mapping.getId()).block().getProjectId()
    );
    assertTrue(
      as("admin", bindingManager.history(device.getId()).collectList())
        .block()
        .isEmpty()
    );
  }

  @Test
  void confirmingSameProjectPreservesInstallationAndBinding() {
    var c = new FacilityContext("tenant", "app", "admin", "Admin");
    var device = FacilityScope.initialize(new DeviceEntity(), c);
    device.setId("same-project-device");
    device.setProjectId("p");
    device.setSpaceId("house");
    device.setCandidateProjectIds(List.of("p", "q"));
    device.setEnergyEnabled(true);
    devices.save(device).block();
    var project =
      new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
    project.setId("p");
    when(directory.project("p")).thenReturn(Mono.just(project));
    doReturn(Mono.empty()).when(records).assertProject(eq("p"), any());
    as("admin", deviceManager.confirmProject(device.getId(), "p", 0)).block();
    var saved = devices.findById(device.getId()).block();
    assertEquals("house", saved.getSpaceId());
    assertTrue(saved.getEnergyEnabled());
  }

  @Test
  void projectConfirmationCannotReactivateCloudDeactivatedDevice() {
    for (String projectId : List.of("p", "q")) {
      var device = FacilityScope.initialize(new DeviceEntity(),
        new FacilityContext("tenant", "app", "admin", "Admin"));
      device.setId("inactive-confirm-" + projectId);
      device.setProjectId("p");
      device.setOrganizationId("p");
      device.setSourceState("notActive");
      device.setSyncState("inactive");
      device.setCandidateProjectIds(List.of("p", "q"));
      device.setEnergyEnabled(true);
      devices.save(device).block();
      when(directory.project(projectId)).thenReturn(Mono.just(project(projectId)));
      doReturn(Mono.empty()).when(records).assertProject(eq(projectId), any());
      var confirmed = as("admin", deviceManager.confirmProject(device.getId(), projectId, 0)).block();
      assertEquals(projectId, confirmed.getProjectId());
      assertEquals("inactive", confirmed.getSyncState());
      assertEquals("inactive", devices.findById(device.getId()).block().getSyncState());
      assertThrows(BusinessException.class, () -> FacilityDevices.requireActive(confirmed));
    }
  }

  @Test
  void rejectedTaskClearsOldSubmissionKeysInDatabase() {
    task("single");
    as("worker", manager.action("test", "claim", command(0))).block();
    as(
      "worker",
      manager.action("test", "submit", submit(1, "original-submission"))
    ).block();
    var reject = command(2);
    reject.setComment("请补充检查");
    as("reviewer", manager.action("test", "reject", reject)).block();
    var saved = store.findById("test").block();
    assertEquals("rectify", saved.getState());
    assertNull(saved.getSubmissionId());
    assertEquals(
      "review",
      as(
        "worker",
        manager.action("test", "submit", submit(3, "corrected-submission"))
      )
        .block()
        .getState()
    );
  }

  @Test
  void extensionImportUsesCloudIdentityAndRollsBackOnStaleRow() {
    var c = new FacilityContext("tenant", "app", "admin", "Admin");
    for (String cloudId : List.of("import-one", "import-two")) {
      var row = FacilityScope.initialize(new DeviceEntity(), c);
      row.setId(FacilityRecords.stableId("tenant", "app", cloudId));
      row.setCloudDeviceId(cloudId);
      bindSource(cloudId); row.setSyncState("active"); row.setResponsibleUserId("employee");
      row.setProjectId("p");
      row.setName("Original");
      row.setManufacturer("Original manufacturer");
      row.setEnergyEnabled(true);
      devices.save(row).block();
    }
    var first = new FacilityDevices.ExtensionImport(
      "import-one",
      0L,
      Map.of("manufacturer", "Changed", "responsibleUserId", "employee")
    );
    var stale = new FacilityDevices.ExtensionImport(
      "import-two",
      99L,
      Map.of("manufacturer", "Must not apply", "responsibleUserId", "employee")
    );
    StepVerifier.create(
      as("admin", deviceManager.importExtensions(List.of(first, stale)))
    )
      .expectError(BusinessException.class)
      .verify();
    var id = FacilityRecords.stableId("tenant", "app", "import-one");
    assertEquals(
      "Original manufacturer",
      devices.findById(id).block().getManufacturer()
    );
    as("admin", deviceManager.importExtensions(List.of(first))).block();
    var saved = devices.findById(id).block();
    assertEquals("Changed", saved.getManufacturer());
    assertEquals("Original", saved.getName());
    assertTrue(saved.getEnergyEnabled());
    assertEquals("import-one", saved.getCloudDeviceId());
  }

  @Test
  void extensionPreviewAndImportValidateRequiredProjectOwnerAndPreserveBlankValues() {
    String cloudId = "profile-import";
    var row = FacilityScope.initialize(new DeviceEntity(), new FacilityContext("tenant", "app", "admin", "Admin"));
    row.setId(FacilityRecords.stableId("tenant", "app", cloudId)); row.setCloudDeviceId(cloudId);
    row.setProjectId("p"); row.setName("原设备"); row.setSyncState("active"); row.setResponsibleUserId("old");
    row.setManufacturer("原厂商"); row.setLocation("原位置"); row.setEnergyEnabled(true);
    row.setInspectionEnabled(true); row.setMaintenanceEnabled(true); devices.save(row).block(); bindSource(cloudId);
    var person = new PropertyUserInfo(); person.setId("employee"); person.setUsername("owner@project");
    when(directory.projectEmployeeAccounts("p")).thenReturn(Flux.just(person));
    var space = new org.jetlinks.pro.property.base.space.PropertySpaceInfo(); space.setId("room"); space.setFullName("12栋/201");
    when(directory.spaces("p")).thenReturn(Flux.just(space));
    when(directory.space("p", "room")).thenReturn(Mono.just(space));
    var input = new FacilityDevices.ExtensionImport(cloudId, 0L, Map.of("responsibleAccount", "owner@project",
      "spacePath", "12栋/201", "manufacturer", "", "location", " ", "workEnabled", false, "energyEnabled", false), 8);
    var checks = as("admin", deviceManager.previewExtensions(List.of(input))).block();
    assertEquals(8, checks.get(0).rowNumber()); assertTrue(checks.get(0).valid());
    assertEquals(0L, devices.findById(row.getId()).block().getVersion());
    assertEquals(0L, logs.createQuery().where("targetId", row.getId()).count().block().longValue());
    as("admin", deviceManager.importExtensions(List.of(input))).block();
    var saved = devices.findById(row.getId()).block();
    assertEquals("employee", saved.getResponsibleUserId()); assertEquals("room", saved.getSpaceId());
    assertEquals("原厂商", saved.getManufacturer()); assertEquals("原位置", saved.getLocation());
    assertFalse(saved.getEnergyEnabled()); assertFalse(saved.getInspectionEnabled()); assertFalse(saved.getMaintenanceEnabled());
    long version = saved.getVersion();
    for (var values : List.<Map<String,Object>>of(Map.of("responsibleAccount", ""), Map.of("responsibleAccount", "other"),
      Map.of("responsibleAccount", "owner@project", "spacePath", "不存在"), Map.of("responsibleUserId", "employee", "purchaseDate", "2026-02-30"),
      Map.of("responsibleUserId", "employee", "power", -1), Map.of("responsibleUserId", "employee", "energyEnabled", "true"))) {
      var invalid = new FacilityDevices.ExtensionImport(cloudId, version, values);
      assertFalse(as("admin", deviceManager.previewExtensions(List.of(invalid))).block().get(0).valid());
      StepVerifier.create(as("admin", deviceManager.importExtensions(List.of(invalid)))).expectError().verify();
      assertEquals(version, devices.findById(row.getId()).block().getVersion());
    }
    when(directory.projectEmployee("p", "foreign")).thenReturn(Mono.error(new BusinessException("error.facility.project_employee_required")));
    var foreign = new FacilityDevices.ExtensionImport(cloudId, version, Map.of("responsibleUserId", "foreign"));
    assertFalse(as("admin", deviceManager.previewExtensions(List.of(foreign))).block().get(0).valid());
    StepVerifier.create(as("admin", deviceManager.importExtensions(List.of(foreign)))).expectError().verify();
    assertEquals("employee", devices.findById(row.getId()).block().getResponsibleUserId());
  }

  @Test
  void extensionImportRejectsSourceFieldsAndUnlinkedDevices() {
    StepVerifier.create(
      as(
        "admin",
        deviceManager.importExtensions(
          List.of(
            new FacilityDevices.ExtensionImport(
              "unknown",
              0L,
              Map.of("projectId", "other")
            )
          )
        )
      )
    )
      .expectError(BusinessException.class)
      .verify();
    StepVerifier.create(
      as(
        "admin",
        deviceManager.importExtensions(
          List.of(
            new FacilityDevices.ExtensionImport(
              "unknown",
              0L,
              Map.of("manufacturer", "Factory")
            )
          )
        )
      )
    )
      .expectError(org.hswebframework.web.exception.NotFoundException.class)
      .verify();
  }

  @Autowired
  FacilityReports reportsService;

  @Autowired
  EnergyTargetStore targets;

  @Test
  void energyReportSelectsOneUsageMetricAndUsesPlanUnits() {
    var c = new FacilityContext("tenant", "app", "admin", "Admin");
    long from = java.time.LocalDate.of(2023, 1, 1)
      .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
      .toInstant()
      .toEpochMilli();
    long to = java.time.LocalDate.of(2023, 2, 1)
      .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
      .toInstant()
      .toEpochMilli();
    var mapping = FacilityScope.initialize(new MappingEntity(), c);
    mapping.setId("report-mapping");
    mapping.setDeviceId("report-device");
    mapping.setProjectId("report-project");
    mapping.setName("Main water");
    mapping.setEnergyType("water");
    mapping.setMeterLevel("master");
    mapping.setUsagePropertyId("total");
    mapping.setStaleAfterSeconds(4000000L);
    var total = new MetricMapping();
    total.setPropertyId("total");
    total.setKind("counter");
    total.setUnit("m³");
    var secondary = new MetricMapping();
    secondary.setPropertyId("secondary");
    secondary.setKind("counter");
    secondary.setUnit("m³");
    mapping.setMetrics(List.of(total, secondary));
    mappings.save(mapping).block();
    for (String property : List.of("total", "secondary"))
      for (int index = 0; index < 2; index++) {
        var reading = FacilityScope.initialize(new ReadingEntity(), c);
        reading.setId("report-" + property + index);
        reading.setDeviceId("report-device");
        reading.setProjectId("report-project");
        reading.setPropertyId(property);
        reading.setMetricKind("counter");
        reading.setStatus("accepted");
        reading.setUnit("m³");
        reading.setValue(
          java.math.BigDecimal.valueOf(
            (property.equals("total") ? 30 : 1000) * index
          )
        );
        reading.setObservedTime(index == 0 ? from : to);
        reading.setMappingVersion(0L);
        reading.setMeterEpoch(0L);
        readings.save(reading).block();
      }
    var data = as(
      "admin",
      reportsService.energy("report-project", from, to, "master")
    ).block();
    assertEquals(1, data.size());
    assertEquals("total", data.get(0).get("propertyId"));
    var usage = (EnergyMath.Usage) data.get(0).get("usage");
    assertEquals(0, new java.math.BigDecimal("30").compareTo(usage.value()));
    assertTrue(usage.complete());
    var target = FacilityScope.initialize(new EnergyTargetEntity(), c);
    target.setId("report-target");
    target.setProjectId("report-project");
    target.setYear(2023);
    target.setMonth(1);
    target.setEnergyType("water");
    target.setTargetUsage(new java.math.BigDecimal("20"));
    targets.save(target).block();
    var execution = as(
      "admin",
      reportsService.targetExecution("report-project", 2023, 1)
    ).block();
    assertEquals(1, execution.size());
    assertEquals("m³", execution.get(0).get("unit"));
    assertEquals(true, execution.get(0).get("exceeded"));
    assertEquals(true, execution.get(0).get("complete"));
    assertEquals(
      0,
      new java.math.BigDecimal("30").compareTo(
        (java.math.BigDecimal) execution.get(0).get("actual")
      )
    );
  }

  @Test
  void operationRatesExcludeCancelledAndClosedTasksAndIncludeLateCompletions() {
    var c = new FacilityContext("tenant", "app", "admin", "Admin");
    for (String state : List.of("completed", "cancelled", "closed")) {
      var row = FacilityScope.initialize(new TaskEntity(), c);
      row.setId("rate-" + state);
      row.setProjectId("rate-project");
      row.setKind("inspection");
      row.setScheduledTime(2000L);
      row.setDeadline(2100L);
      row.setState(state);
      if ("completed".equals(state)) row.setCompletedTime(2200L);
      store.save(row).block();
    }
    var result = as(
      "admin",
      reportsService.operations("rate-project", 1000, 3000)
    ).block();
    var taskRows = (List<Map<String, Object>>) result.get("tasks");
    var inspection = taskRows.get(0);
    assertEquals(1, inspection.get("total"));
    assertEquals(1L, inspection.get("completed"));
    assertEquals(1L, inspection.get("overdue"));
    assertEquals(
      new java.math.BigDecimal("100.00"),
      inspection.get("completionRate")
    );
    var overview = as("admin", reportsService.overview("rate-project")).block();
    assertEquals(3, overview.get("tasks"));
    assertEquals(0L, overview.get("overdue"));
    assertEquals(new java.math.BigDecimal("100.00"), overview.get("completionRate"));
  }

  @Autowired
  FacilityAlerts ruleManager;

  @Autowired
  RuleStore ruleStore;

  @Autowired
  ReplacementStore replacementStore;

  @Test
  void decimalBusinessValuesSurviveReloadAndRuleEdit() {
    var context = new FacilityContext("tenant", "app", "staff", "Staff");
    var amount = new java.math.BigDecimal("123.125000000001");
    var rule = alertFixture("decimal-persistence", "instant", "instant");
    rule.setThreshold(new java.math.BigDecimal("5.25"));
    rule.setCriticalThreshold(new java.math.BigDecimal("10.75"));
    rule.setNearRatio(new java.math.BigDecimal("0.8"));
    ruleStore.save(rule).block();
    var reloaded = ruleStore.findById(rule.getId()).block();
    assertEquals(0, rule.getThreshold().compareTo(reloaded.getThreshold()));
    assertEquals(0, rule.getCriticalThreshold().compareTo(reloaded.getCriticalThreshold()));
    assertEquals(0, rule.getNearRatio().compareTo(reloaded.getNearRatio()));
    var project = new PropertyOrganizationInfo();
    project.setId(rule.getProjectId());
    when(directory.project(rule.getProjectId())).thenReturn(Mono.just(project));
    reloaded.setEnabled(false);
    as("staff", ruleManager.save(reloaded)).block();
    assertFalse(ruleStore.findById(rule.getId()).block().getEnabled());

    var device = devices.findById("decimal-persistence-device").block();
    device.setPower(amount);
    device.setPurchasePrice(new java.math.BigDecimal("99999999999999999999999999999999.125000000001"));
    devices.save(device).block();
    var savedDevice = devices.findById(device.getId()).block();
    assertEquals(0, amount.compareTo(savedDevice.getPower()));
    assertEquals(0, device.getPurchasePrice().compareTo(savedDevice.getPurchasePrice()));

    var reading = FacilityScope.initialize(new ReadingEntity(), context);
    reading.setId("decimal-persistence-reading");
    reading.setProjectId(rule.getProjectId());
    reading.setDeviceId(device.getId());
    reading.setRawValue(amount);
    reading.setValue(amount);
    readings.save(reading).block();
    var savedReading = readings.findById(reading.getId()).block();
    assertEquals(0, amount.compareTo(savedReading.getRawValue()));
    assertEquals(0, amount.compareTo(savedReading.getValue()));

    var target = FacilityScope.initialize(new EnergyTargetEntity(), context);
    target.setId("decimal-persistence-target");
    target.setProjectId(rule.getProjectId());
    target.setEnergyType("gas");
    target.setYear(2042);
    target.setMonth(10);
    target.setTargetUsage(amount);
    target.setManagementArea(amount);
    targets.save(target).block();
    var savedTarget = targets.findById(target.getId()).block();
    assertEquals(0, amount.compareTo(savedTarget.getTargetUsage()));
    assertEquals(0, amount.compareTo(savedTarget.getManagementArea()));

    var replacement = FacilityScope.initialize(new ReplacementEntity(), context);
    replacement.setId("decimal-persistence-replacement");
    replacement.setProjectId(rule.getProjectId());
    replacement.setOldDeviceId(device.getId());
    replacement.setFinalReading(amount);
    replacement.setInitialReading(amount);
    replacementStore.save(replacement).block();
    var savedReplacement = replacementStore.findById(replacement.getId()).block();
    assertEquals(0, amount.compareTo(savedReplacement.getFinalReading()));
    assertEquals(0, amount.compareTo(savedReplacement.getInitialReading()));

    var alert = FacilityScope.initialize(new AlertEntity(), context);
    alert.setId("decimal-persistence-alert");
    alert.setProjectId(rule.getProjectId());
    alert.setValue(amount);
    alert.setThreshold(amount);
    alerts.save(alert).block();
    var savedAlert = alerts.findById(alert.getId()).block();
    assertEquals(0, amount.compareTo(savedAlert.getValue()));
    assertEquals(0, amount.compareTo(savedAlert.getThreshold()));
  }

  RuleEntity alertFixture(String prefix, String type, String kind) {
    var c = new FacilityContext("tenant", "app", "staff", "Staff");
    var device = FacilityScope.initialize(new DeviceEntity(), c);
    device.setId(prefix + "-device");
    device.setCloudDeviceId(prefix);
    bindSource(prefix);
    device.setProjectId(prefix + "-project");
    device.setSyncState("active");
    device.setEnergyEnabled(true);
    devices.save(device).block();
    var mapping = FacilityScope.initialize(new MappingEntity(), c);
    mapping.setId(prefix + "-mapping");
    mapping.setDeviceId(device.getId());
    mapping.setProjectId(device.getProjectId());
    mapping.setEnergyType("gas");
    mapping.setEnabled(true);
    mapping.setStaleAfterSeconds(60L);
    mapping.setMeterLevel("master");
    var metric = new MetricMapping();
    metric.setPropertyId("value");
    metric.setKind(kind);
    metric.setUnit("m³");
    mapping.setUsagePropertyId("value");
    mapping.setMetrics(List.of(metric));
    mappings.save(mapping).block();
    var rule = FacilityScope.initialize(new RuleEntity(), c);
    rule.setId(prefix + "-rule");
    rule.setProjectId(device.getProjectId());
    rule.setName(prefix);
    rule.setRuleType(type);
    rule.setEnergyType("gas");
    rule.setPropertyId("value");
    rule.setThreshold(java.math.BigDecimal.ONE);
    rule.setStaleAfterSeconds(60L);
    rule.setRepeatHours(24);
    rule.setEnabled(true);
    ruleStore.save(rule).block();
    return rule;
  }

  void sample(
    RuleEntity rule,
    String id,
    long time,
    String value,
    String text,
    String status,
    String kind
  ) {
    var row = FacilityScope.initialize(
      new ReadingEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    row.setId(id);
    row.setProjectId(rule.getProjectId());
    row.setDeviceId(rule.getName() + "-device");
    row.setPropertyId("value");
    row.setObservedTime(time);
    row.setValue(value == null ? null : new java.math.BigDecimal(value));
    row.setTextValue(text);
    row.setStatus(status);
    row.setMetricKind(kind);
    row.setMeterEpoch(0L);
    row.setMappingVersion(0L);
    readings.save(row).block();
  }

  @Test
  void taskRulesDoNotEmitDuplicateDeviceAggregateAlerts() {
    var rule = alertFixture("soon", "dueSoon", "counter");
    as("staff", ruleManager.evaluate(rule)).block();
    assertNull(alerts.findById(FacilityRecords.stableId(rule.getId(), "soon-device")).block());
    verify(notifier, never()).enqueue(any(), any(), any());
  }

  @Test
  void invalidAndRollbackReadingsProduceOneMergedAnomalyAlert() {
    var rule = alertFixture("anomaly", "reading-anomaly", "counter");
    long now = System.currentTimeMillis();
    sample(rule, "anomaly-a", now - 30000, "100", null, "accepted", "counter");
    sample(rule, "anomaly-b", now - 20000, "10", null, "accepted", "counter");
    sample(rule, "anomaly-c", now - 10000, null, "bad", "invalid", "counter");
    as("staff", ruleManager.evaluate(rule)).block();
    as("staff", ruleManager.evaluate(rule)).block();
    var alert = alerts
      .findById(FacilityRecords.stableId(rule.getId(), "anomaly-device"))
      .block();
    assertNotNull(alert);
    assertEquals(0, new java.math.BigDecimal("2").compareTo(alert.getValue()));
    verify(notifier, times(1)).enqueue(any(), any(), any());
  }

  @Test
  void numericStateRuleMatchesCloudDecimalEncodingAndRecovers() {
    var state = alertFixture("numeric-state", "state", "state");
    state.setStateValue("1");
    long now = System.currentTimeMillis();
    sample(state, "numeric-state-a", now - 10000, null, "1.0000", "accepted", "state");
    as("staff", ruleManager.evaluate(state)).block();
    String id = FacilityRecords.stableId(state.getId(), "numeric-state-device");
    assertEquals("open", alerts.findById(id).block().getState());
    sample(state, "numeric-state-b", now - 5000, null, "0.0000", "accepted", "state");
    as("staff", ruleManager.evaluate(state)).block();
    assertEquals("recovered", alerts.findById(id).block().getState());
    verify(notifier, times(1)).enqueue(any(), any(), any());
  }

  @Test
  void stateRulesRecoverAndStaleBalancesNeverTriggerPaymentReminder() {
    var state = alertFixture("valve", "state", "state");
    state.setStateValue("fault");
    long now = System.currentTimeMillis();
    sample(state, "valve-a", now - 10000, null, "fault", "accepted", "state");
    as("staff", ruleManager.evaluate(state)).block();
    String id = FacilityRecords.stableId(state.getId(), "valve-device");
    assertEquals("open", alerts.findById(id).block().getState());
    sample(state, "valve-b", now - 5000, null, "normal", "accepted", "state");
    as("staff", ruleManager.evaluate(state)).block();
    assertEquals("recovered", alerts.findById(id).block().getState());
    var balance = alertFixture("stale-balance", "balance", "balance");
    sample(
      balance,
      "stale-balance-a",
      now - 120000,
      "0",
      null,
      "accepted",
      "balance"
    );
    as("staff", ruleManager.evaluate(balance)).block();
    assertNull(
      alerts
        .findById(
          FacilityRecords.stableId(balance.getId(), "stale-balance-device")
        )
        .block()
    );
    verify(notifier, times(1)).enqueue(any(), any(), any());
  }

  @Test
  void taskClaimStillRejectsAnEmployeeOutsideTheProjectOrganization() {
    task("double");
    when(directory.taskAssignee(any(), eq("outsider"))).thenReturn(
      Mono.error(new BusinessException("role", 400))
    );
    StepVerifier.create(
      as("outsider", manager.action("test", "claim", command(0)))
    )
      .expectError(BusinessException.class)
      .verify();
    assertEquals("open", store.findById("test").block().getState());
    as("external", manager.action("test", "claim", command(0))).block();
    assertEquals("external", store.findById("test").block().getAssigneeId());
  }

  @Test
  void planExceedanceCreatesProjectEventAndEmployeeOutboxOnly() {
    var rule = alertFixture("plan-event", "plan", "increment");
    rule.setThreshold(new java.math.BigDecimal("100"));
    rule.setNotifyUserId("manager");
    ruleStore.save(rule).block();
    var date = java.time.ZonedDateTime.now(
      java.time.ZoneId.of("Asia/Shanghai")
    );
    var target = FacilityScope.initialize(
      new EnergyTargetEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    target.setId("plan-event-target");
    target.setProjectId(rule.getProjectId());
    target.setEnergyType("gas");
    target.setYear(date.getYear());
    target.setMonth(date.getMonthValue());
    target.setTargetUsage(new java.math.BigDecimal("10"));
    targets.save(target).block();
    sample(
      rule,
      "plan-event-a",
      System.currentTimeMillis() - 1000,
      "12",
      null,
      "accepted",
      "increment"
    );
    as("staff", ruleManager.evaluate(rule)).block();
    as("staff", ruleManager.evaluate(rule)).block();
    var alert = alerts
      .findById(FacilityRecords.stableId(rule.getId(), target.getId()))
      .block();
    assertNotNull(alert);
    assertNull(alert.getDeviceId());
    assertEquals(target.getId(), alert.getTargetId());
    assertEquals(
      0,
      new java.math.BigDecimal("120").compareTo(alert.getValue())
    );
    verify(notifier, times(1)).enqueueTarget(any(), any(), any());
    verify(notifier, never()).enqueue(any(), any(), any());
  }

  @Autowired
  FacilityReadings readingManager;

  @Test
  void replayBatchesSkipExistingReadingsButKeepLateSamplesAndVersionGuard() {
    String prefix = "batch-backfill";
    alertFixture(prefix, "usage", "counter");
    var mapping = mappings.findById(prefix + "-mapping").block();
    var metric = mapping.getMetrics().get(0);
    metric.setMultiplier(java.math.BigDecimal.ONE);
    mappings.save(mapping).block();
    var first = sample(prefix, 3000L, 10);
    var late = sample(prefix, 2000L, 12);
    assertNotNull(as("staff", readingManager.ingest(mapping, metric, first)).block());
    // Replaying a source must not overwrite its accepted original value.
    first.setValue(999);
    var inserted = as("staff", readingManager.ingestBatch(mapping, metric, List.of(first, late, late)).collectList()).block();
    assertEquals(1, inserted.size());
    assertEquals(2000L, inserted.get(0).getObservedTime());
    assertEquals(2, readings.createQuery().where("mappingId", mapping.getId()).count().block());
    var original = readings.createQuery().where("mappingId", mapping.getId())
      .and("observedTime", 3000L).fetchOne().block();
    assertEquals(0, new java.math.BigDecimal("10").compareTo(original.getValue()));
    assertTrue(as("staff", readingManager.ingestBatch(mapping, metric, List.of(first, late)).collectList())
      .block().isEmpty());
    var invalid = sample("another-cloud-device", 4000L, 15);
    StepVerifier.create(as("staff", readingManager.ingestBatch(mapping, metric, List.of(invalid)).then()))
      .expectError(BusinessException.class).verify();
    mappings.createUpdate().set("enabled", false).where("id", mapping.getId()).execute().block();
    StepVerifier.create(as("staff", readingManager.ingestBatch(mapping, metric, List.of(sample(prefix, 4000L, 15))).then()))
      .verifyComplete();
    assertEquals(2, readings.createQuery().where("mappingId", mapping.getId()).count().block());
  }

  private static org.jetlinks.sdk.server.device.DeviceProperty sample(String cloudId, long time, int value) {
    var sample = new org.jetlinks.sdk.server.device.DeviceProperty();
    sample.setDeviceId(cloudId);
    sample.setProperty("value");
    sample.setValue(value);
    sample.setTimestamp(time);
    return sample;
  }

  @Test
  void oldBackfillCannotWinDedupeAfterMappingEditOrDisable() {
    String prefix = "mapping-backfill-version";
    alertFixture(prefix, "usage", "counter");
    var stale = mappings.findById(prefix + "-mapping").block();
    var oldMetric = stale.getMetrics().get(0);
    oldMetric.setMultiplier(new java.math.BigDecimal("2"));
    mappings.save(stale).block();

    var current = mappings.findById(stale.getId()).block();
    var newMetric = new MetricMapping();
    newMetric.setPropertyId("value");
    newMetric.setKind("increment");
    newMetric.setUnit("m³");
    newMetric.setMultiplier(new java.math.BigDecimal("3"));
    current.setMetrics(List.of(newMetric));
    current.setVersion(stale.getVersion() + 1);
    mappings.save(current).block();

    var sample = new org.jetlinks.sdk.server.device.DeviceProperty();
    sample.setDeviceId(prefix);
    sample.setProperty("value");
    sample.setValue(10);
    sample.setTimestamp(1000L);
    StepVerifier.create(as("staff", readingManager.ingest(stale, oldMetric, sample)))
      .verifyComplete();
    var accepted = as("staff", readingManager.ingest(current, newMetric, sample)).block();
    assertNotNull(accepted);
    assertEquals("increment", accepted.getMetricKind());
    assertEquals(0, new java.math.BigDecimal("30").compareTo(accepted.getValue()));
    assertEquals(current.getVersion(), accepted.getMappingVersion());
    var duplicate = as("staff", readingManager.ingest(current, newMetric, sample)).block();
    assertEquals(accepted.getId(), duplicate.getId());

    mappings.createUpdate().set("enabled", false).where("id", current.getId()).execute().block();
    sample.setTimestamp(2000L);
    StepVerifier.create(as("staff", readingManager.ingest(current, newMetric, sample)))
      .verifyComplete();
    assertEquals(1, readings.createQuery().where("mappingId", current.getId()).count().block());
    assertEquals(current.getVersion(), mappings.findById(current.getId()).block().getVersion());
  }

  private FacilityNotifications notificationDispatcher(String deviceId) {
    var execution = mock(FacilityExecution.class);
    when(execution.asOwner(any(), any())).thenAnswer(call -> {
      NotificationEntity row = call.getArgument(0);
      if (!deviceId.equals(row.getDeviceId())) return Mono.empty();
      java.util.function.Supplier<Mono<Void>> work = call.getArgument(1);
      return as("staff", Mono.defer(work));
    });
    return spy(new FacilityNotifications(
      deviceManager, targets, alerts, readingManager, directory, records,
      notifications, bindings, bindingManager, devices, ruleStore, execution, mock(FacilityEmployeeMessages.class, RETURNS_SMART_NULLS)
    ));
  }

  private NotificationEntity pendingEmployeeNotice(String prefix, FacilityNotifications dispatcher) {
    var rule = ruleStore.findById(prefix + "-rule").block();
    var device = devices.findById(prefix + "-device").block();
    device.setResponsibleUserId("employee");
    devices.save(device).block();
    var alert = FacilityScope.initialize(new AlertEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff"));
    alert.setId(prefix + "-alert");
    alert.setProjectId(device.getProjectId());
    alert.setDeviceId(device.getId());
    alert.setRuleId(rule.getId());
    alert.setState("open");
    alert.setNotificationSequence(1);
    alerts.save(alert).block();
    // 已持久化旧站内发件箱继续验证重试/撤权；新员工消息直接写统一收件箱，不再经过provider。
    var legacy = FacilityScope.initialize(new NotificationEntity(), new FacilityContext("tenant", "app", "staff", "Staff"));
    FacilityScope.inherit(legacy, device); legacy.setDeviceId(device.getId()); legacy.setEventId(alert.getId());
    legacy.setUserId("employee"); legacy.setRecipientKey("employee"); legacy.setChannel("inside"); legacy.setSequence(1);
    legacy.setState("pending"); legacy.setAttempts(0); legacy.setNextAttemptTime(System.currentTimeMillis());
    notifications.insert(legacy).block();
    return notifications.createQuery().where("deviceId", device.getId()).fetchOne().block();
  }

  @Test
  void providerPermissionRejectionRemainsRetryableAndSuccessfulRetryClearsReason() {
    alertFixture("delivery-denied", "instant", "instant");
    var dispatcher = notificationDispatcher("delivery-denied-device");
    var notice = pendingEmployeeNotice("delivery-denied", dispatcher);
    doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()))
      .when(dispatcher).sendInside(any());
    dispatcher.poll().block();
    var failed = notifications.findById(notice.getId()).block();
    assertEquals("failed", failed.getState());
    assertEquals("delivery_permission_denied", failed.getFailureReason());
    assertEquals(1, failed.getAttempts());
    dispatcher.poll().block();
    verify(dispatcher, times(1)).sendInside(any());

    doReturn(Mono.empty()).when(dispatcher).sendInside(any());
    as("staff", dispatcher.retry(notice.getId())).block();
    dispatcher.poll().block();
    var sent = notifications.findById(notice.getId()).block();
    assertEquals("sent", sent.getState());
    assertNull(sent.getFailureReason());
    assertEquals(1, sent.getAttempts());
    dispatcher.poll().block();
    verify(dispatcher, times(2)).sendInside(any());
  }

  @Test
  void obsoleteEmployeeRecipientCancelsBeforeDispatchWithReason() {
    alertFixture("recipient-cancelled", "instant", "instant");
    var dispatcher = notificationDispatcher("recipient-cancelled-device");
    var notice = pendingEmployeeNotice("recipient-cancelled", dispatcher);
    devices.createUpdate().set("responsibleUserId", "new-employee")
      .where("id", notice.getDeviceId()).execute().block();
    dispatcher.poll().block();
    var cancelled = notifications.findById(notice.getId()).block();
    assertEquals("cancelled", cancelled.getState());
    assertEquals("eligibility_permission_denied", cancelled.getFailureReason());
    assertEquals(0, cancelled.getAttempts());
    verify(dispatcher, never()).sendInside(any());
  }

  @Test void legacyCustomerSmsIsCancelledBeforeDispatchAndCannotBeRetried() {
    alertFixture("retired-sms", "instant", "instant");
    var dispatcher = notificationDispatcher("retired-sms-device");
    var notice = pendingEmployeeNotice("retired-sms", dispatcher);
    notifications.createUpdate().set("channel", "sms").set("userId", "customer").where("id", notice.getId()).execute().block();
    dispatcher.poll().block();
    var retired = notifications.findById(notice.getId()).block();
    assertEquals("cancelled", retired.getState()); assertEquals("employee_channel_only", retired.getFailureReason());
    assertEquals(0, retired.getAttempts()); verify(dispatcher, never()).sendInside(any());
    notifications.createUpdate().set("state", "failed").where("id", notice.getId()).execute().block();
    as("staff", dispatcher.retry(notice.getId())).block();
    assertEquals("cancelled", notifications.findById(notice.getId()).block().getState());
    assertEquals("employee_channel_only", notifications.findById(notice.getId()).block().getFailureReason());
  }

  @Test
  void delayedBalanceNotificationRechecksFreshnessRecoveryAndRecipient() {
    var rule = alertFixture("delayed", "balance", "balance");
    var device = devices.findById("delayed-device").block();
    device.setResponsibleUserId("employee");
    devices.save(device).block();
    as(
      "staff",
      alertWriter.record(
        rule,
        device,
        java.math.BigDecimal.ZERO,
        "m³",
        System.currentTimeMillis(),
        "current"
      )
    ).block();
    var alert = alerts
      .findById(FacilityRecords.stableId(rule.getId(), device.getId()))
      .block();
    var row = FacilityScope.initialize(
      new NotificationEntity(),
      new FacilityContext("tenant", "app", "staff", "Staff")
    );
    row.setId("delayed-notification");
    row.setProjectId(device.getProjectId());
    row.setDeviceId(device.getId());
    row.setEventId(alert.getId());
    row.setSequence(alert.getNotificationSequence());
    row.setUserId("employee");
    row.setRecipientKey("employee");
    row.setChannel("inside");
    row.setState("failed");
    row.setAttempts(1);
    row.setNextAttemptTime(0L);
    notifications.save(row).block();
    var realNotifier = new FacilityNotifications(
      deviceManager,
      targets,
      alerts,
      readingManager,
      directory,
      records,
      notifications,
      bindings,
      bindingManager,
      devices,
      ruleStore,
      mock(FacilityExecution.class),
      mock(FacilityEmployeeMessages.class, RETURNS_SMART_NULLS)
    );
    sample(
      rule,
      "delayed-stale",
      System.currentTimeMillis() - 120000,
      "0",
      null,
      "accepted",
      "balance"
    );
    StepVerifier.create(as("staff", realNotifier.retry(row.getId())))
      .expectError(
        org.hswebframework.web.authorization.exception.AccessDenyException.class
      )
      .verify();
    assertEquals(
      "failed",
      notifications.findById(row.getId()).block().getState()
    );
    sample(
      rule,
      "delayed-fresh",
      System.currentTimeMillis() - 1000,
      "0",
      null,
      "accepted",
      "balance"
    );
    as("staff", realNotifier.retry(row.getId())).block();
    assertEquals(
      "pending",
      notifications.findById(row.getId()).block().getState()
    );
    devices
      .createUpdate()
      .set("responsibleUserId", "new-employee")
      .where("id", device.getId())
      .execute()
      .block();
    StepVerifier.create(as("staff", realNotifier.retry(row.getId())))
      .expectError(
        org.hswebframework.web.authorization.exception.AccessDenyException.class
      )
      .verify();
    devices
      .createUpdate()
      .set("responsibleUserId", "employee")
      .where("id", device.getId())
      .execute()
      .block();
    alerts
      .createUpdate()
      .set("state", "recovered")
      .where("id", alert.getId())
      .execute()
      .block();
    StepVerifier.create(as("staff", realNotifier.retry(row.getId())))
      .expectError(
        org.hswebframework.web.authorization.exception.AccessDenyException.class
      )
      .verify();
  }

  @Test
  void clearedRuleDeviceAndOptionalThresholdArePersistedAsNull() {
    var rule = alertFixture("clear-rule", "usage", "counter");
    rule.setDeviceId("clear-rule-device");
    rule.setPeriod("day");
    rule.setCriticalThreshold(new java.math.BigDecimal("5"));
    ruleStore.save(rule).block();
    var project =
      new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
    project.setId(rule.getProjectId());
    when(directory.project(rule.getProjectId())).thenReturn(Mono.just(project));
    doReturn(Mono.empty())
      .when(records)
      .assertProject(eq(rule.getProjectId()), any());
    rule.setDeviceId(null);
    rule.setCriticalThreshold(null);
    as("staff", ruleManager.save(rule)).block();
    var saved = ruleStore.findById(rule.getId()).block();
    assertNull(saved.getDeviceId());
    assertNull(saved.getCriticalThreshold());
    assertEquals("day", saved.getPeriod());
  }

  @Test
  void requiredDeviceAssignmentIsRetainedAndNumericExtensionCanBeCleared() {
    var rule = alertFixture("clear-device", "usage", "counter");
    var device = devices.findById("clear-device-device").block();
    device.setResponsibleUserId("old-employee");
    device.setPower(java.math.BigDecimal.TEN);
    devices.save(device).block();
    device.setResponsibleUserId(null);
    StepVerifier.create(as("staff", deviceManager.update(device.getId(), device)))
      .expectErrorMatches(error -> error.getMessage().contains("device_responsible_required")).verify();
    assertEquals("old-employee", devices.findById(device.getId()).block().getResponsibleUserId());
    device.setResponsibleUserId("new-employee");
    device.setPower(null);
    as("staff", deviceManager.update(device.getId(), device)).block();
    var saved = devices.findById(device.getId()).block();
    assertEquals("new-employee", saved.getResponsibleUserId());
    assertNull(saved.getPower());
    assertEquals(device.getCloudDeviceId(), saved.getCloudDeviceId());
    assertTrue(saved.getEnergyEnabled());
  }

  @Autowired CycleStore cycleStore;
  @Autowired PlanStore planStore;
  @Autowired StandardStore standardStore;
  @Autowired GroupStore groupStore;
  @Autowired FacilityReminderMigration reminderMigration;
  @Autowired FacilityTaskReminders taskReminders;
  @Autowired FacilityCycles cycleManager;
  @Autowired FacilityPlans planManager;

  PlanEntity cyclicPlan(String suffix) {
    long now = System.currentTimeMillis() / 60000 * 60000;
    var cycle = FacilityScope.initialize(new CycleEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    cycle.setId("cycle-" + suffix); cycle.setName("水泵巡检周期"); cycle.setProjectId("p"); cycle.setOrganizationId("p");
    cycle.setKind("inspection"); cycle.setEnabled(true);
    var weekly = new java.util.HashMap<Integer, List<CycleRule.Window>>();
    for (int day = 1; day <= 7; day++) weekly.put(day, List.of(new CycleRule.Window(0, 0, 1, 1, 1, "1", "1", 0.5d, "1")));
    cycle.setRule(new CycleRule(weekly, Map.of()));
    cycleStore.insert(cycle).block();
    var standard = FacilityScope.initialize(new FacilityStandardEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    standard.setId("standard-" + suffix); standard.setProjectId("p"); standard.setKind("inspection"); standard.setEnabled(true);
    var item = new CheckItem(); item.setId("pressure"); item.setName("水泵压力正常"); item.setKind("choice");
    item.setOptions(List.of("符合", "不符合")); standard.setCheckItems(List.of(item)); standardStore.insert(standard).block();
    var device = FacilityScope.initialize(new DeviceEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    device.setId("pump-" + suffix); device.setCloudDeviceId("cloud-pump-" + suffix); device.setProjectId("p");
    bindSource(device.getCloudDeviceId());
    device.setName("消防水泵"); device.setSyncState("active"); device.setInspectionEnabled(true); devices.insert(device).block();
    var group = FacilityScope.initialize(new GroupEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    group.setId("room-" + suffix); group.setProjectId("p"); group.setDeviceIds(List.of(device.getId(), device.getId())); groupStore.insert(group).block();
    var plan = FacilityScope.initialize(new PlanEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    plan.setId("config-" + suffix); plan.setName("消防水泵任务配置"); plan.setProjectId("p"); plan.setOrganizationId("p");
    plan.setCycleId(cycle.getId()); plan.setStandardId(standard.getId()); plan.setKind("inspection"); plan.setMode("single");
    plan.setGroupIds(List.of(group.getId(), group.getId())); plan.setReviewerId("reviewer"); plan.setEnabled(true);
    plan.setStartTime(now - 600000); plan.setEndTime(now + 3600000); plan.setNextRunTime(now); planStore.insert(plan).block();
    return plan;
  }

  @Test void cycleGeneratesWholeTaskAndDeviceSnapshotsWithStableIds() {
    var plan = cyclicPlan("once");
    // Even an old double configuration can only generate unassigned project work orders now.
    plan.setMode("double"); plan.setAssigneeId("old-contractor");
    plan.setFollowUserId("old-follower"); plan.setExternalOrganizationId("old-vendor");
    planStore.save(plan).block();
    var result = as("staff", planManager.generate(plan.getId())).block();
    assertEquals(1, result.size());
    var task = result.get(0);
    assertEquals(plan.getNextRunTime(), task.getScheduledTime());
    assertEquals(plan.getNextRunTime() + 60000, task.getDeadline());
    assertEquals("open", task.getState());
    assertEquals(plan.getCycleId(), task.getCycleId());
    assertNull(task.getWarningTime());
    assertEquals(1, task.getDeviceCount());
    assertEquals(0, task.getSubmittedDeviceCount());
    var child = store.createQuery().where("parentTaskId", task.getId()).fetchOne().block();
    assertEquals("pump-once", child.getDeviceId());
    assertEquals(List.of("room-once"), child.getGroupIds());
    assertEquals("open", child.getState());
    assertNull(child.getAssigneeId());
    assertNull(task.getAssigneeId());
    assertNull(task.getMode()); assertNull(task.getFollowUserId()); assertNull(task.getExternalOrganizationId());
    assertEquals("水泵压力正常", task.getStandardSnapshot().get(0).getName());
    assertEquals(plan.getNextRunTime() + 60000, planStore.findById(plan.getId()).block().getNextRunTime());
    // Replay the same slot as after a retry; the stable ID reuses the committed work order.
    planStore.createUpdate().set("nextRunTime", plan.getNextRunTime()).where("id", plan.getId()).execute().block();
    assertEquals(task.getId(), as("staff", planManager.generate(plan.getId())).block().get(0).getId());
    assertEquals(2, store.createQuery().where("planId", plan.getId()).count().block());
    assertEquals(0L, cycleStore.findById(plan.getCycleId()).block().getVersion());
  }

  @Test void revokedDeviceStopsNewTasksAndAlertsButKeepsOldWorkAndResumesSameDeviceAfterRebinding() {
    var plan = cyclicPlan("binding-lifecycle");
    var first = as("staff", planManager.generate(plan.getId())).block().get(0);
    long nextSlot = plan.getNextRunTime() - 60000;
    planStore.createUpdate().set("nextRunTime", nextSlot).where("id", plan.getId()).execute().block();
    boundSources.clear();
    assertTrue(as("staff", planManager.generate(plan.getId())).block().isEmpty());
    assertEquals(2, store.createQuery().where("planId", plan.getId()).count().block());
    assertEquals(first.getId(), as("staff", manager.read(first.getId())).block().getId());
    assertNotNull(devices.findById("pump-binding-lifecycle").block());
    bindSource("cloud-pump-binding-lifecycle");
    planStore.createUpdate().set("nextRunTime", nextSlot).where("id", plan.getId()).execute().block();
    var resumed = as("staff", planManager.generate(plan.getId())).block().get(0);
    assertEquals(1, resumed.getDeviceCount());
    assertEquals("pump-binding-lifecycle", store.createQuery().where("parentTaskId", resumed.getId()).fetchOne().block().getDeviceId());

    var rule = alertFixture("revoked-offline", "offline", "counter");
    var device = devices.findById("revoked-offline-device").block(); device.setSourceState("offline");
    device.setSourceStateSince(System.currentTimeMillis() - 60000); devices.save(device).block();
    boundSources.remove(device.getCloudDeviceId());
    as("staff", ruleManager.evaluate(rule)).block();
    assertEquals(0, alerts.createQuery().where("ruleId", rule.getId()).count().block());
    bindSource(device.getCloudDeviceId());
    as("staff", ruleManager.evaluate(rule)).block();
    assertEquals(1, alerts.createQuery().where("ruleId", rule.getId()).count().block());
  }

  @Test void wholeTaskClaimsAndReviewsAllDevicesButEachDeviceSubmitsIndependently() {
    var plan = cyclicPlan("whole");
    var second = devices.findById("pump-whole").block();
    second.setId("pump-whole-2"); second.setCloudDeviceId("cloud-whole-2"); second.setName("二号水泵");
    bindSource(second.getCloudDeviceId());
    devices.insert(second).block();
    var group = groupStore.findById("room-whole").block();
    group.setDeviceIds(List.of("pump-whole", second.getId())); groupStore.save(group).block();
    var anotherGroup = FacilityScope.initialize(new GroupEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    anotherGroup.setId("room-whole-2"); anotherGroup.setProjectId("p"); anotherGroup.setDeviceIds(List.of(second.getId()));
    groupStore.insert(anotherGroup).block();
    plan.setGroupIds(List.of(group.getId(), anotherGroup.getId())); planStore.save(plan).block();
    var parent = as("staff", planManager.generate(plan.getId())).block().get(0);
    assertEquals(2, parent.getDeviceCount());
    var children = as("worker", manager.devices(parent.getId(), null, null)).block();
    assertEquals(2, children.size());
    assertEquals(1, as("worker", manager.devices(parent.getId(), anotherGroup.getId(), null)).block().size());
    var list = as("worker", manager.query(QueryParamEntity.newQuery().is("planId", plan.getId()).getParam(), false)).block();
    assertEquals(List.of(parent.getId()), list.getData().stream().map(TaskEntity::getId).toList());
    var claimed = as("worker", manager.action(parent.getId(), "claim", command(0))).block();
    for (var child : as("worker", manager.devices(parent.getId(), null, null)).block()) {
      assertEquals("worker", child.getAssigneeId()); assertEquals("worker", child.getAssigneeName());
      assertEquals("claimed", child.getState()); assertEquals(1L, child.getVersion());
      StepVerifier.create(as("worker", manager.action(child.getId(), "claim", command(1))))
        .expectError(BusinessException.class).verify();
    }
    StepVerifier.create(as("worker", manager.action(parent.getId(), "submit", submit(claimed.getVersion(), "invalid-parent"))))
      .expectError(BusinessException.class).verify();
    var check = new CheckResult(); check.setItemId("pressure"); check.setValue("符合"); check.setAttachments(List.of());
    for (int i = 0; i < children.size(); i++) {
      var cmd = command(1); cmd.setSubmissionId("whole-device-" + i); cmd.setResults(List.of(check));
      as("worker", manager.action(children.get(i).getId(), "submit", cmd)).block();
      var progress = store.findById(parent.getId()).block();
      assertEquals(i + 1, progress.getSubmittedDeviceCount());
      assertEquals(i == 0 ? "executing" : "review", progress.getState());
      // Exact replay neither increments the device version nor changes whole-task progress/version.
      as("worker", manager.action(children.get(i).getId(), "submit", cmd)).block();
      assertEquals(progress.getVersion(), store.findById(parent.getId()).block().getVersion());
    }
    var reject = command(store.findById(parent.getId()).block().getVersion()); reject.setComment("重拍现场照片");
    as("reviewer", manager.action(parent.getId(), "reject", reject)).block();
    assertEquals(0, store.findById(parent.getId()).block().getSubmittedDeviceCount());
    for (var child : as("worker", manager.devices(parent.getId(), null, null)).block()) {
      assertEquals("rectify", child.getState()); assertNull(child.getSubmissionId());
      assertEquals("重拍现场照片", child.getReviewComment()); assertEquals(1, child.getResults().size());
      var cmd = command(child.getVersion()); cmd.setResults(List.of(check)); cmd.setSubmissionId("redo-" + child.getId());
      as("worker", manager.action(child.getId(), "submit", cmd)).block();
    }
    var ready = store.findById(parent.getId()).block();
    assertEquals("review", ready.getState());
    as("reviewer", manager.action(parent.getId(), "approve", command(ready.getVersion()))).block();
    for (var child : as("worker", manager.devices(parent.getId(), null, null)).block()) {
      assertEquals("completed", child.getState()); assertNotNull(child.getCompletedTime());
    }
  }

  @Test void wholeTaskTransferClearsAllDeviceResultsAndClosingRejectsLateSubmissions() {
    var plan = cyclicPlan("whole-transfer");
    var parent = as("staff", planManager.generate(plan.getId())).block().get(0);
    as("worker", manager.action(parent.getId(), "claim", command(0))).block();
    var child = as("worker", manager.devices(parent.getId(), null, null)).block().get(0);
    as("worker", manager.action(child.getId(), "start", command(child.getVersion()))).block();
    var transfer = command(store.findById(parent.getId()).block().getVersion()); transfer.setUserId("next");
    as("dispatcher", manager.action(parent.getId(), "transfer", transfer)).block();
    child = store.findById(child.getId()).block();
    assertEquals("next", child.getAssigneeId()); assertEquals("claimed", child.getState());
    assertTrue(child.getResults().isEmpty()); assertNull(child.getSubmissionId());
    var closed = as("dispatcher", manager.action(parent.getId(), "close", command(store.findById(parent.getId()).block().getVersion()))).block();
    assertEquals("closed", store.findById(child.getId()).block().getState());
    StepVerifier.create(as("next", manager.action(child.getId(), "submit", submit(child.getVersion(), "late"))))
      .expectError(BusinessException.class).verify();
    assertEquals("closed", store.findById(closed.getId()).block().getState());
  }

  @Test void cycleEditsStopAndResumeOnlyWithinConfigurationDatesWithoutRewritingTasks() {
    var plan = cyclicPlan("edit");
    var generated = as("staff", planManager.generate(plan.getId())).block().get(0);
    var project = new PropertyOrganizationInfo(); project.setId("p"); project.setName("项目");
    when(directory.project("p")).thenReturn(Mono.just(project));
    doReturn(Mono.empty()).when(records).assertProject(eq("p"), any());
    var cycle = cycleStore.findById(plan.getCycleId()).block();
    cycle.setEnabled(false);
    as("staff", cycleManager.save(cycle)).block();
    assertEquals(Long.MAX_VALUE, planStore.findById(plan.getId()).block().getNextRunTime());
    long futureStart = System.currentTimeMillis() + 86400000;
    planStore.createUpdate().set("startTime", futureStart).set("endTime", futureStart + 3600000)
      .where("id", plan.getId()).execute().block();
    cycle = cycleStore.findById(plan.getCycleId()).block(); cycle.setEnabled(true);
    as("staff", cycleManager.save(cycle)).block();
    var resumed = planStore.findById(plan.getId()).block();
    assertTrue(resumed.getNextRunTime() >= futureStart && resumed.getNextRunTime() < resumed.getEndTime());
    assertEquals(2, store.createQuery().where("planId", plan.getId()).count().block());
    assertEquals(generated.getScheduledTime(), store.findById(generated.getId()).block().getScheduledTime());
    assertEquals(generated.getDeadline(), store.findById(generated.getId()).block().getDeadline());
  }

  @Test void cycleGenerationRollsBackTasksWhenCursorCannotAdvance() {
    var plan = cyclicPlan("rollback");
    doReturn(Mono.error(new BusinessException("cursor-conflict"))).when(records).update(eq(planStore), any(PlanEntity.class), anyLong());
    try {
      StepVerifier.create(as("staff", planManager.generate(plan.getId()))).expectError(BusinessException.class).verify();
      assertEquals(0, store.createQuery().where("planId", plan.getId()).count().block());
      assertEquals(plan.getNextRunTime(), planStore.findById(plan.getId()).block().getNextRunTime());
    } finally { doCallRealMethod().when(records).update(eq(planStore), any(PlanEntity.class), anyLong()); }
  }

  @Test void concurrentGenerationCommitsOnlyOneTaskAndOneCursorStep() {
    var plan = cyclicPlan("concurrent");
    var outcomes = Flux.merge(as("staff", planManager.generate(plan.getId())).materialize(),
      as("staff", planManager.generate(plan.getId())).materialize()).collectList().block();
    assertTrue(outcomes.stream().anyMatch(reactor.core.publisher.Signal::isOnNext));
    assertEquals(2, store.createQuery().where("planId", plan.getId()).count().block());
    assertEquals(plan.getNextRunTime() + 60000, planStore.findById(plan.getId()).block().getNextRunTime());
  }

  @Test void referencedCycleCannotBeDeletedAndUnauthorizedCycleCannotGenerate() {
    var plan = cyclicPlan("permission");
    StepVerifier.create(as("staff", cycleManager.remove(plan.getCycleId(), 0)))
      .expectErrorMatches(e -> e instanceof BusinessException && "error.facility.cycle_in_use".equals(e.getMessage())).verify();
    when(scope.assertRecord(any(CycleEntity.class), eq(org.jetlinks.pro.property.facility.asset.FacilityAssetType.facilityCycle), any(), any()))
      .thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    StepVerifier.create(as("staff", planManager.generate(plan.getId())))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    assertEquals(0, store.createQuery().where("planId", plan.getId()).count().block());
  }

  @Autowired AlertEpisodeStore episodeStore;
  @Autowired FacilityFaults faultManager;

  @Test void alertEpisodesPreserveHandlingAcrossRecoveryAndStartFreshRound() {
    var rule = alertFixture("episode-history", "instant", "instant");
    var device = devices.findById("episode-history-device").block(); long now = System.currentTimeMillis();
    as("staff", alertWriter.record(rule, device, java.math.BigDecimal.TEN, "A", now, "current")).block();
    String id = FacilityRecords.stableId(rule.getId(), device.getId());
    var active = alerts.findById(id).block();
    as("staff", ruleManager.action(id, "closed", "已检查接线，等待设备恢复", active.getVersion())).block();
    assertEquals("open", alerts.findById(id).block().getState());
    assertEquals("closed", alerts.findById(id).block().getProcessingState());
    as("staff", alertWriter.record(rule, device, java.math.BigDecimal.ZERO, "A", now + 1000, "current")).block();
    as("staff", alertWriter.record(rule, device, java.math.BigDecimal.TEN, "A", now + 2000, "current")).block();
    var old = episodeStore.findById(FacilityRecords.stableId(id, Long.toString(now))).block();
    assertEquals("closed", old.getProcessingState()); assertEquals("recovered", old.getState());
    assertEquals("已检查接线，等待设备恢复", old.getComment()); assertEquals(now + 1000, old.getRecoveredTime());
    var current = alerts.findById(id).block(); assertEquals("open", current.getProcessingState()); assertNull(current.getComment());
    assertEquals(2, episodeStore.createQuery().where("episodeId", id).count().block());
  }

  @Test void faultLifecyclePersistsAuditAndRefusesWrongActorAndStaleVersion() {
    alertFixture("fault-lifecycle", "instant", "instant");
    when(directory.projectEmployee(anyString(), anyString())).thenAnswer(call -> Mono.just(
      new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo(call.getArgument(1), call.getArgument(1), null, List.of(call.<String>getArgument(0)))));
    var input = new FaultEntity(); input.setDeviceId("fault-lifecycle-device"); input.setDescription("水泵异常振动");
    input.setReviewerId("reviewer"); input.setAssigneeId("worker"); input.setDeadline(System.currentTimeMillis() + 3600000);
    var dispatchAuth = mock(org.hswebframework.web.authorization.Authentication.class);
    when(dispatchAuth.hasPermission("facility-fault", "dispatch")).thenReturn(true);
    var row = as("staff", faultManager.create(input))
      .contextWrite(context -> context.put(org.hswebframework.web.authorization.Authentication.class, dispatchAuth)).block();
    assertEquals("assigned", row.getState());
    String faultId = row.getId();
    StepVerifier.create(as("other", faultManager.action(faultId, "claim", new org.jetlinks.pro.property.facility.model.FaultCommand(0L, null, null, List.of())))).expectError(BusinessException.class).verify();
    row = as("worker", faultManager.action(faultId, "claim", new org.jetlinks.pro.property.facility.model.FaultCommand(0L, null, null, List.of()))).block();
    row = as("worker", faultManager.action(faultId, "start", new org.jetlinks.pro.property.facility.model.FaultCommand(row.getVersion(), null, null, List.of()))).block();
    row = as("worker", faultManager.action(faultId, "resolve", new org.jetlinks.pro.property.facility.model.FaultCommand(row.getVersion(), "已更换轴承", null, List.of()))).block();
    long version = row.getVersion();
    StepVerifier.create(as("worker", faultManager.action(faultId, "approve", new org.jetlinks.pro.property.facility.model.FaultCommand(version, null, null, List.of())))).expectError(BusinessException.class).verify();
    row = as("reviewer", faultManager.action(faultId, "reject", new org.jetlinks.pro.property.facility.model.FaultCommand(version, "补充运行检查", null, List.of()))).block();
    row = as("worker", faultManager.action(faultId, "resolve", new org.jetlinks.pro.property.facility.model.FaultCommand(row.getVersion(), "试运行正常", null, List.of()))).block();
    long submittedVersion = row.getVersion();
    StepVerifier.create(as("staff", faultManager.action(faultId, "reviewer", new FaultCommand(submittedVersion, "原负责人离职", "no-review", List.of()))))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    assertEquals("reviewer", faults.findById(faultId).block().getReviewerId());
    row = as("staff", faultManager.action(faultId, "reviewer", new FaultCommand(submittedVersion, "原负责人离职", "replacement", List.of()))).block();
    assertEquals("review", row.getState()); assertEquals("试运行正常", row.getResolution());
    long replacedVersion = row.getVersion();
    StepVerifier.create(as("reviewer", faultManager.action(faultId, "approve", new FaultCommand(replacedVersion, null, null, List.of()))))
      .expectError(BusinessException.class).verify();
    row = as("replacement", faultManager.action(faultId, "approve", new org.jetlinks.pro.property.facility.model.FaultCommand(row.getVersion(), "验收通过", null, List.of()))).block();
    assertEquals("closed", faults.findById(faultId).block().getState()); assertNotNull(row.getCompletedTime());
    assertEquals(8, logs.createQuery().where("targetId", faultId).count().block());
  }

  @Test void creatingAssignedFaultRequiresDispatchPermissionBeforeAnyWrite() {
    var input = new FaultEntity(); input.setDeviceId("unread-device"); input.setDescription("水泵漏水");
    input.setReviewerId("reviewer"); input.setAssigneeId("worker"); input.setDeadline(System.currentTimeMillis() + 3600000);
    var auth = mock(org.hswebframework.web.authorization.Authentication.class);
    when(auth.hasPermission("facility-fault", "dispatch")).thenReturn(false);
    StepVerifier.create(as("staff", faultManager.create(input))
      .contextWrite(context -> context.put(org.hswebframework.web.authorization.Authentication.class, auth)))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    assertEquals(0, faults.createQuery().where("deviceId", "unread-device").count().block());
  }

  @Test void faultParticipantsNeedCurrentBusinessPermissionAndProjectMembership() {
    alertFixture("fault-participant", "instant", "instant");
    when(directory.projectEmployee(anyString(), anyString())).thenAnswer(call -> Mono.just(
      new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo(call.getArgument(1), call.getArgument(1), null, List.of(call.<String>getArgument(0)))));
    var input = new FaultEntity(); input.setDeviceId("fault-participant-device"); input.setDescription("水泵异常");
    input.setReviewerId("no-review"); input.setDeadline(System.currentTimeMillis() + 3600000);
    StepVerifier.create(as("staff", faultManager.create(input)))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    input.setReviewerId("reviewer"); input.setAssigneeId("no-save");
    var auth = mock(org.hswebframework.web.authorization.Authentication.class);
    when(auth.hasPermission("facility-fault", "dispatch")).thenReturn(true);
    StepVerifier.create(as("staff", faultManager.create(input)).contextWrite(c -> c.put(org.hswebframework.web.authorization.Authentication.class, auth)))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    input.setAssigneeId(null); input.setReviewerId("foreign-project");
    when(directory.projectEmployee(anyString(), eq("foreign-project"))).thenReturn(Mono.error(new BusinessException("error.facility.project_employee_required", 400)));
    StepVerifier.create(as("staff", faultManager.create(input))).expectError(BusinessException.class).verify();
    assertEquals(0, faults.createQuery().where("deviceId", "fault-participant-device").count().block());
  }

  @Autowired FacilityInbox inbox;
  @Autowired NotificationStore inboxStore;

  @Test void taskInboxPersistsDeduplicatesPagesAndMarksOnlyThisUserRead() {
    when(scope.query(any(), eq(FacilityAssetType.facilityTask), eq("taskId"))).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      return scope.current().map(context -> FacilityScope.applicationQuery(query, context));
    });
    var task = task("single"); task.setId("inbox-root"); task.setDeviceCount(3);
    task.setScheduledTime(1000L); task.setDeadline(61000L); task.setProjectId("inbox-project");
    var writer = new FacilityTaskMessages(store, inboxStore, null, null, null, null, null);
    writer.store(task,"new","inbox-worker").block(); writer.store(task,"new","inbox-worker").block();
    Mono.when(writer.store(task,"new","inbox-race"), writer.store(task,"new","inbox-race")).block();
    assertEquals(1,inboxStore.createQuery().where("userId","inbox-race").count().block());
    writer.store(task,"warning","inbox-worker").block(); writer.store(task,"overdue","inbox-other").block();
    var otherApp = FacilityTaskMessages.message(task,"new","inbox-worker");
    otherApp.setId("inbox-other-app"); otherApp.setApplicationId("another-app"); inboxStore.insert(otherApp).block();
    assertEquals(2,as("inbox-worker",inbox.unread("inbox-project")).block());
    var query = QueryParamEntity.newQuery().where("projectId","inbox-project").getParam();
    query.setPageSize(1);
    var page=as("inbox-worker",inbox.page(query)).block();
    assertEquals(2,page.getTotal()); assertEquals(1,page.getData().size());
    assertEquals(3,page.getData().get(0).getDeviceCount());
    String otherId=FacilityTaskMessages.message(task,"overdue","inbox-other").getId();
    as("inbox-worker",inbox.read(otherId,null)).block();
    assertNull(inboxStore.findById(otherId).block().getReadTime());
    as("inbox-worker",inbox.read(null,"inbox-project")).block();
    assertEquals(0,as("inbox-worker",inbox.unread("inbox-project")).block());
    assertEquals(1,as("inbox-other",inbox.unread("inbox-project")).block());
    var approval = FacilityTaskMessages.message(task, "task:review", "inbox-worker");
    approval.setChannel("employee"); inboxStore.insert(approval).block();
    assertEquals(1, as("inbox-worker", inbox.unread("inbox-project")).block());
    var employeeMessages = as("inbox-worker", inbox.page(QueryParamEntity.newQuery()
      .is("messageType", "task:review").getParam())).block();
    assertEquals(1, employeeMessages.getTotal());
    assertEquals(approval.getId(), employeeMessages.getData().get(0).getId());
    assertEquals(1, as("inbox-other", inbox.unread("inbox-project")).block());
    assertNull(inboxStore.findById("inbox-other-app").block().getReadTime());
  }

  @Test void saasAlarmsPersistReplayRecoverAndRebindWithoutResettingManualWork() {
    var device = FacilityScope.initialize(new DeviceEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    device.setId("saas-meter"); device.setCloudDeviceId("cloud-saas-meter"); device.setProjectId("p-a");
    device.setName("12-201水表"); device.setLocation("12-201"); device.setResponsibleUserId("worker");
    devices.insert(device).block(); bindSource(device.getCloudDeviceId());
    clearInvocations(employeeMessages);
    when(employeeMessages.platformAlert(any(), any())).thenReturn(Mono.empty());
    var source = new CloudAlarmRecord(); source.setId("cloud-alarm-record"); source.setAlarmConfigId("cloud-rule");
    source.setSourceType("device"); source.setSourceId(device.getCloudDeviceId()); source.setAlarmName("持续用水告警");
    source.setAlarmTime(1000L); source.setLastAlarmTime(1500L); source.setLevel(2); source.setState("warning"); source.setActualDesc("持续用水超过30分钟");
    String id = FacilityRecords.stableId("tenant", "app", device.getId(), "saas", source.getId());
    as("admin", saasWriter.accept(device, source)).block();
    var saved = alerts.findById(id).block();
    assertEquals("saas", saved.getSource()); assertEquals("12-201", saved.getSpaceName());
    assertEquals("持续用水超过30分钟", saved.getSourceReason()); assertEquals(1, saved.getNotificationSequence());
    as("admin", saasWriter.accept(device, source)).block();
    source.setLastAlarmTime(2000L); as("admin", saasWriter.accept(device, source)).block();
    verify(employeeMessages, times(1)).platformAlert(any(), any());
    alerts.createUpdate().set("processingState", "closed").set("comment", "阀门已检查").where("id", id).execute().block();
    source.setState(Map.of("value", "normal")); source.setHandleTime(3000L);
    as("admin", saasWriter.acceptRelief(device, source, saved, "现场确认已排除异常")).block();
    var reliefLog = logs.createQuery().where("targetId", id).and("action", "relieve").fetchOne().block();
    assertNotNull(reliefLog); assertEquals("admin", reliefLog.getCreatorId());
    assertEquals("现场确认已排除异常", reliefLog.getComment()); assertEquals("recovered", reliefLog.getToState());
    saved = alerts.findById(id).block();
    assertEquals("recovered", saved.getState()); assertEquals(3000, saved.getRecoveredTime());
    assertEquals("closed", saved.getProcessingState()); assertEquals("阀门已检查", saved.getComment());
    source.setState("warning"); source.setAlarmTime(4000L); source.setLastAlarmTime(4000L);
    as("admin", saasWriter.accept(device, source)).block();
    saved = alerts.findById(id).block(); assertEquals("open", saved.getProcessingState()); assertNull(saved.getComment()); assertNull(saved.getRecoveredTime());
    assertEquals(2, episodeStore.createQuery().where("episodeId", id).count().block());
    var old = episodeStore.findById(FacilityRecords.stableId(id, "1000")).block();
    assertEquals("closed", old.getProcessingState()); assertEquals("阀门已检查", old.getComment());
    source.setLevel(4); source.setLastAlarmTime(5000L); as("admin", saasWriter.accept(device, source)).block();
    as("admin", saasWriter.accept(device, source)).block();
    verify(employeeMessages, times(3)).platformAlert(any(), any());
    assertEquals(4, alerts.findById(id).block().getSourceLevel());
    boundSources.remove(device.getCloudDeviceId());
    StepVerifier.create(as("admin", saasWriter.accept(device, source))).expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    assertEquals(1, alerts.createQuery().where("id", id).count().block());
    bindSource(device.getCloudDeviceId()); as("admin", saasWriter.accept(device, source)).block();
    verify(employeeMessages, times(3)).platformAlert(any(), any());
    source.setAlarmTime(1000L); as("admin", saasWriter.accept(device, source)).block();
    assertEquals(4000, alerts.findById(id).block().getFirstTime());
    StepVerifier.create(as("admin", saasWriter.acceptRelief(device, source, saved, "过期轮次"))).expectError().verify();
    assertEquals(1, logs.createQuery().where("targetId", id).and("action", "relieve").count().block());
    source.setId("rollback"); source.setAlarmTime(6000L); source.setLastAlarmTime(6000L);
    when(employeeMessages.platformAlert(any(), any())).thenReturn(Mono.error(new IllegalStateException("message store unavailable")));
    StepVerifier.create(as("admin", saasWriter.accept(device, source))).expectError(IllegalStateException.class).verify();
    assertEquals(0, alerts.createQuery().where("sourceRecordId", "rollback").count().block());
  }

  @Test void reliefAuditFailureRollsBackLocalRecoveryAndKeepsHandlingState() {
    var device = FacilityScope.initialize(new DeviceEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    device.setId("relief-rollback-device"); device.setCloudDeviceId("relief-rollback-cloud"); device.setProjectId("p-a");
    devices.insert(device).block(); bindSource(device.getCloudDeviceId());
    when(employeeMessages.platformAlert(any(), any())).thenReturn(Mono.empty());
    var source = new CloudAlarmRecord(); source.setId("relief-rollback-source"); source.setSourceType("device");
    source.setSourceId(device.getCloudDeviceId()); source.setAlarmTime(1000L); source.setState("warning");
    as("admin", saasWriter.accept(device, source)).block();
    String id = FacilityRecords.stableId("tenant", "app", device.getId(), "saas", source.getId());
    alerts.createUpdate().set("processingState", "closed").set("comment", "已检查").where("id", id).execute().block();
    var before = alerts.findById(id).block();
    source.setState("normal"); source.setHandleTime(2000L);
    doReturn(Mono.error(new IllegalStateException("audit unavailable"))).when(records)
      .log(argThat(row -> row != null && id.equals(row.getId())), eq("alert"), eq("relieve"), anyString(), anyString(), anyString(), anyList());
    StepVerifier.create(as("admin", saasWriter.acceptRelief(device, source, before, "异常已排除")))
      .expectError(IllegalStateException.class).verify();
    var after = alerts.findById(id).block();
    assertEquals("open", after.getState()); assertEquals("closed", after.getProcessingState()); assertEquals("已检查", after.getComment());
    assertNull(after.getRecoveredTime()); assertEquals(before.getVersion(), after.getVersion());
    assertEquals(0, logs.createQuery().where("targetId", id).count().block());
  }

  @Test void currentAlarmListExcludesRetiredRulesAndUnboundDevicesWithoutDeletingHistory() {
    var device = FacilityScope.initialize(new DeviceEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    device.setId("current-alarm-device"); device.setCloudDeviceId("current-cloud-device"); device.setProjectId("p-a");
    devices.insert(device).block(); bindSource(device.getCloudDeviceId());
    var current = FacilityScope.initialize(new AlertEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    current.setId("current-platform-alarm"); current.setDeviceId(device.getId()); current.setProjectId("p-a"); current.setRuleType("saas"); current.setSource("saas");
    alerts.insert(current).block();
    var legacy = FacilityScope.initialize(new AlertEntity(), new FacilityContext("tenant", "app", "admin", "管理员"));
    legacy.setId("retired-local-alarm"); legacy.setDeviceId(device.getId()); legacy.setProjectId("p-a"); legacy.setRuleType("offline");
    alerts.insert(legacy).block();
    var controller = new org.jetlinks.pro.property.facility.web.FacilityAlertController(alerts, null, null, null, queries, null, null, null, deviceManager, null, scope, null);
    var filter = QueryParamEntity.newQuery().is("id", current.getId()).or("id", legacy.getId()).getParam();
    var page = as("admin", controller.query(filter)).block();
    assertEquals(List.of(current.getId()), page.getData().stream().map(AlertEntity::getId).toList());
    boundSources.remove(device.getCloudDeviceId());
    assertEquals(0, as("admin", controller.query(filter)).block().getTotal());
    assertNotNull(alerts.findById(current.getId()).block()); assertNotNull(alerts.findById(legacy.getId()).block());
    bindSource(device.getCloudDeviceId());
    assertEquals(1, as("admin", controller.query(filter)).block().getTotal());
  }

  @Test void cycleRemindersMigrateOnceRetainOwnershipAndDeletingRulesDoesNotRecreateThem() {
    var plan=cyclicPlan("reminder-migration");
    var original=cycleStore.findById(plan.getCycleId()).block();
    as("staff",reminderMigration.migrate(plan.getCycleId())).block();
    var migrated=cycleStore.findById(plan.getCycleId()).block();
    assertTrue(migrated.getRemindersMigrated());
    var rows=ruleStore.createQuery().where("cycleId",plan.getCycleId()).fetch().collectList().block();
    assertEquals(2,rows.size());
    for(var row:rows) {
      assertEquals(original.getCreatorId(),row.getCreatorId()); assertEquals(original.getTenantId(),row.getTenantId());
      assertEquals(original.getApplicationId(),row.getApplicationId()); assertEquals(original.getProjectId(),row.getProjectId());
      assertEquals("inspection",row.getTaskKind());
    }
    var soon=rows.stream().filter(row -> "dueSoon".equals(row.getRuleType())).findFirst().orElseThrow();
    assertEquals(original.getRule(),soon.getLegacySchedule());
    ruleStore.createDelete().where("cycleId",plan.getCycleId()).execute().block();
    as("staff",reminderMigration.migrate(plan.getCycleId())).block();
    assertEquals(0,ruleStore.createQuery().where("cycleId",plan.getCycleId()).count().block());
  }

  @Test void migrationRollbackKeepsOriginalCycleAndLeavesNoPartialRules() {
    var plan=cyclicPlan("reminder-rollback");
    var collision=FacilityScope.initialize(new RuleEntity(),new FacilityContext("tenant","app","staff","Staff"));
    collision.setId(FacilityRecords.stableId(plan.getCycleId(),"task-reminder","dueSoon"));
    ruleStore.insert(collision).block();
    StepVerifier.create(as("staff",reminderMigration.migrate(plan.getCycleId()))).expectError().verify();
    assertFalse(Boolean.TRUE.equals(cycleStore.findById(plan.getCycleId()).block().getRemindersMigrated()));
    assertNull(ruleStore.findById(FacilityRecords.stableId(plan.getCycleId(),"task-reminder","overdue")).block());
  }

  @Test void centralReminderRulesMatchActiveTasksAndStopAfterDisableWithoutSuppressingNewTaskDispatch() {
    var task=FacilityScope.initialize(new TaskEntity(),new FacilityContext("tenant","app","staff","Staff"));
    task.setId("central-reminder-task"); task.setProjectId("central-project"); task.setKind("inspection");
    task.setState("claimed"); task.setScheduledTime(1000L); task.setDeadline(61000L);
    assertEquals("",as("staff",taskReminders.category(task,31000)).block());
    var rule=FacilityScope.initialize(new RuleEntity(),new FacilityContext("tenant","app","staff","Staff"));
    rule.setProjectId(task.getProjectId()); rule.setRuleType("dueSoon"); rule.setStaleAfterSeconds(30L);
    rule.setTaskKind("inspection"); rule.setEnabled(true); ruleStore.insert(rule).block();
    assertEquals("warning",as("staff",taskReminders.category(task,31000)).block());
    var overlapping=FacilityScope.initialize(new RuleEntity(),new FacilityContext("tenant","app","staff","Staff"));
    overlapping.setProjectId(task.getProjectId()); overlapping.setRuleType("dueSoon"); overlapping.setStaleAfterSeconds(60L);
    overlapping.setEnabled(true); ruleStore.insert(overlapping).block();
    task.setAssigneeId("reminder-worker"); task.setReviewerId("reminder-reviewer");
    var policies=mock(FacilityNotificationPolicies.class); when(policies.enabled(any(),eq("task"))).thenReturn(Mono.just(true));
    var messages=spy(new FacilityTaskMessages(store,inboxStore,null,directory,records,policies,taskReminders));
    doReturn(Mono.just(true)).when(messages).eligible(any(),anyString(),anyString());
    String type=as("staff",taskReminders.category(task,31000)).block();
    messages.enqueue(task,type).block(); messages.enqueue(task,type).block();
    assertEquals(2,inboxStore.createQuery().where("taskId",task.getId()).count().block());
    overlapping.setEnabled(false); ruleStore.save(overlapping).block();
    rule.setEnabled(false); ruleStore.save(rule).block();
    assertEquals("",as("staff",taskReminders.category(task,31000)).block());
    task.setState("open"); assertEquals("new",as("staff",taskReminders.category(task,31000)).block());
    task.setState("review"); assertEquals("",as("staff",taskReminders.category(task,31000)).block());
  }

  @Test void historicalTasksResolveOriginalPlanCycleAndNewTasksUseTheirCycleSnapshot() {
    var plan=cyclicPlan("historical-reminder");
    long start=plan.getNextRunTime();
    var task=FacilityScope.initialize(new TaskEntity(),new FacilityContext("tenant","app","staff","Staff"));
    task.setId("historical-reminder-task"); task.setProjectId(plan.getProjectId()); task.setKind(plan.getKind());
    task.setPlanId(plan.getId()); task.setState("claimed"); task.setScheduledTime(start); task.setDeadline(start+60000);
    task.setWarningTime(start+40000);
    assertEquals("",as("staff",taskReminders.category(task,start+39999)).block());
    assertEquals("warning",as("staff",taskReminders.category(task,start+40000)).block());
    task.setCycleId(plan.getCycleId()); task.setPlanId(null); task.setWarningTime(null);
    assertEquals("warning",as("staff",taskReminders.category(task,start+30000)).block());
    assertEquals("overdue",as("staff",taskReminders.category(task,start+60000)).block());
  }

  @Test void savingCentralRulesChecksCycleProjectKindAndProtectsLegacySnapshots() {
    when(directory.project(anyString())).thenAnswer(call -> Mono.just(project(call.getArgument(0))));
    var plan=cyclicPlan("rule-scope");
    var rule=new RuleEntity(); rule.setName("巡检到期提醒"); rule.setProjectId("p"); rule.setRuleType("dueSoon");
    rule.setCycleId(plan.getCycleId()); rule.setTaskKind("maintenance"); rule.setStaleAfterSeconds(600L); rule.setEnabled(true);
    StepVerifier.create(as("staff",ruleManager.save(rule))).expectError(BusinessException.class).verify();
    rule.setTaskKind("inspection"); rule.setProjectId("p-b");
    StepVerifier.create(as("staff",ruleManager.save(rule))).expectError(BusinessException.class).verify();
    rule.setProjectId("p"); rule.setLegacySchedule(cycleStore.findById(plan.getCycleId()).block().getRule());
    StepVerifier.create(as("staff",ruleManager.save(rule))).expectError(BusinessException.class).verify();
    rule.setLegacySchedule(null);
    var saved=as("staff",ruleManager.save(rule)).block();
    assertEquals("inspection",saved.getTaskKind()); assertEquals(plan.getCycleId(),saved.getCycleId());
    saved.setCycleId(null); saved.setTaskKind(null);
    as("staff",ruleManager.save(saved)).block();
    assertNull(ruleStore.findById(saved.getId()).block().getCycleId());
    assertNull(ruleStore.findById(saved.getId()).block().getTaskKind());
  }

  @Test void newCycleHasNoImplicitReminderAndEditingLegacyCyclePreservesRemindersBeforeClearingWarningFields() {
    when(directory.project(anyString())).thenAnswer(call -> Mono.just(project(call.getArgument(0))));
    var input=new CycleEntity(); input.setName("每日巡检"); input.setProjectId("p"); input.setKind("inspection");
    input.setRule(new CycleRule(Map.of(1,List.of(new CycleRule.Window(480,720,60,1,60,"2","2"))),Map.of())); input.setEnabled(true);
    var saved=as("staff",cycleManager.save(input)).block();
    assertTrue(saved.getRemindersMigrated());
    as("staff",reminderMigration.migrate(saved.getId())).block();
    assertEquals(0,ruleStore.createQuery().where("cycleId",saved.getId()).count().block());
    var plan=cyclicPlan("cycle-edit-migration"); var old=cycleStore.findById(plan.getCycleId()).block();
    as("staff",cycleManager.save(old)).block();
    assertNull(cycleStore.findById(old.getId()).block().getRule().weekly().get(1).get(0).warningMinutes());
    assertEquals(2,ruleStore.createQuery().where("cycleId",old.getId()).count().block());
  }

  @SpringBootConfiguration
  @EnableTransactionManagement(proxyTargetClass = true)
  @EnableEasyormRepository("org.jetlinks.pro.property.facility.entity")
  @Import({
    CycleStore.class, PlanStore.class, StandardStore.class, FacilityCycles.class, FacilityPlans.class, FacilityReminderMigration.class, FacilityTaskReminders.class,
    TaskStore.class,
    FaultStore.class,
    ProcessStore.class,
    JobStore.class,
    FacilityQueries.class,
    FacilityRecords.class,
    FacilityTasks.class,
    FacilityFaults.class,
    FacilityTaskExecutions.class,
    BindingStore.class,
    DeviceStore.class,
    MappingStore.class,
    ReadingStore.class,
    NotificationStore.class,
    FacilityInbox.class,
    AlertStore.class,
    FacilityOwner.class,
    FacilityAlertWriter.class,
    AlertEpisodeStore.class,
    FacilityAlertEpisodes.class,
    FacilityDevices.class,
    FacilityBindings.class,
    FacilityReports.class,
    FacilityReadings.class,
    FacilityMappings.class,
    ReplacementStore.class,
    EnergyTargetStore.class,
    RuleStore.class,
    GroupStore.class,
    FacilityAlerts.class,
    FacilitySaasAlarmWriter.class,
  })
  @ImportAutoConfiguration({
    EasyormConfiguration.class,
    R2dbcSqlExecutorConfiguration.class,
    R2dbcAutoConfiguration.class,
    R2dbcTransactionManagerAutoConfiguration.class,
    TransactionAutoConfiguration.class,
    EntityFactoryConfiguration.class,
  })
  static class Config {

    @Bean FacilityExecution faultExecution() { return mock(FacilityExecution.class); }

    @Bean
    org.jetlinks.pro.assets.AssetBindManager bindings() {
      var bindings = mock(org.jetlinks.pro.assets.AssetBindManager.class);
      when(bindings.bindAssets(any())).thenReturn(Mono.empty());
      when(bindings.unbindAssets(any())).thenReturn(Mono.empty());
      return bindings;
    }

    @Bean
    FacilityEmployeeMessages employeeMessages() {
      var messages = mock(FacilityEmployeeMessages.class);
      when(messages.task(any(), anyString())).thenReturn(Mono.empty());
      when(messages.fault(any(), anyString())).thenReturn(Mono.empty());
      return messages;
    }

    @Bean
    FacilityCloud cloud() {
      return mock(FacilityCloud.class);
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper mapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Bean
    FacilityNotifications notifier() {
      return mock(FacilityNotifications.class);
    }

    @Bean
    FacilityScope scope() {
      return mock(FacilityScope.class);
    }

    @Bean
    FacilityDirectory directory() {
      return mock(FacilityDirectory.class);
    }

    @Bean
    FacilityFiles files() {
      return mock(FacilityFiles.class);
    }
  }
}
