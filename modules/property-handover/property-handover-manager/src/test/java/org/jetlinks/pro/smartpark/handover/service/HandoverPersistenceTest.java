package org.jetlinks.pro.smartpark.handover.service;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;

import java.util.List;
import java.util.Set;

/** 真实PostgreSQL验证状态、权限、幂等、CAS竞争及子表失败的事务回滚。 */
@SpringBootTest(
        classes = HandoverPersistenceFixture.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HandoverPersistenceFixture.database(registry);
    }

    @Autowired HandoverScope scope;
    @Autowired HandoverDirectory directory;
    @Autowired HandoverProblemService service;
    @Autowired HandoverConfigurationService configuration;
    @Autowired HandoverProblemRepository problems;
    @Autowired HandoverPhotoRepository photos;
    @Autowired HandoverNodeRepository nodes;
    @Autowired HandoverCategoryRepository categories;
    @Autowired HandoverItemRepository items;
    @Autowired HandoverResponsibilityRepository responsibilities;
    @Autowired HandoverProcessService processes;
    @Autowired HandoverProcessRepository processRows;
    @Autowired HandoverStageLogRepository stageLogs;
    @Autowired HandoverRecordRepository records;
    @Autowired HandoverStatisticsService statistics;
    private final Context employee =
            access("tester", Set.of("project", "external-a", "external-b"));

    @BeforeEach
    void prepare() {
        Mono.when(
                        problems.createDelete()
                                .in("tenantId", List.of("tenant", "other-tenant"))
                                .execute(),
                        photos.createDelete().where("tenantId", "tenant").execute(),
                        nodes.createDelete().where("tenantId", "tenant").execute(),
                        items.createDelete().where("tenantId", "tenant").execute(),
                        categories.createDelete().where("tenantId", "tenant").execute(),
                        responsibilities.createDelete().where("tenantId", "tenant").execute(),
                        processRows.createDelete().where("tenantId", "tenant").execute(),
                        stageLogs.createDelete().where("tenantId", "tenant").execute(),
                        records.createDelete().where("tenantId", "tenant").execute())
                .block(WAIT);
        HandoverPersistenceFixture.directory(directory, true, List.of("external-a", "external-b"));
    }

    @Test
    void offlineReplayReturnsOneProblemWithBothPhotoTypesAndRejectsChangedPayload() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        ProblemRequest request = request("offline-1");
        FileRef before = photo("before", "problem");
        FileRef after = photo("after", "rectification");
        request.setPhotos(List.of(before, after));
        HandoverProblemEntity created = service.create(request).contextWrite(employee).block(WAIT);
        HandoverProblemEntity replay = service.create(request).contextWrite(employee).block(WAIT);
        assertEquals(created.getId(), replay.getId());
        assertEquals(1, problems.createQuery().count().block(WAIT));
        assertEquals(
                Set.of("problem", "rectification"),
                replay.getPhotos().stream()
                        .map(FileRef::getPhotoType)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, replay.getNodes().size());
        assertEquals("tester", replay.getNodes().get(0).getOperatorId());
        request.setDescription("同一个客户端标识的另一份内容");
        assertThrows(
                BusinessException.class,
                () -> service.create(request).contextWrite(employee).block(WAIT));
        assertEquals(1, nodes.createQuery().count().block(WAIT));
    }

    @Test
    void childInsertFailureRollsBackProblemAndItemVersion() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        HandoverItemEntity item = item("item");
        items.insert(item).block(WAIT);
        ProblemRequest request = request("rollback");
        request.setSource("PHYSICAL_HANDOVER");
        request.setItemId("item");
        FileRef photo = photo("file", "problem");
        photo.setName("x".repeat(300));
        request.setPhotos(List.of(photo));
        assertThrows(
                RuntimeException.class,
                () -> service.create(request).contextWrite(employee).block(WAIT));
        assertEquals(0, problems.createQuery().count().block(WAIT));
        assertEquals(0, photos.createQuery().count().block(WAIT));
        assertEquals(0, items.findById("item").block(WAIT).getVersion());
        request.setPhotos(List.of());
        assertNotNull(service.create(request).contextWrite(employee).block(WAIT));
        assertEquals(1, items.findById("item").block(WAIT).getVersion());
    }

    @Test
    void staleReviewMixedBatchDoesNotChangeAnyProblemOrNode() {
        HandoverProblemEntity first = row("first", "external-a", 2, 1);
        HandoverProblemEntity second = row("second", "external-a", 2, 0);
        problems.insertBatch(Mono.just(List.of(first, second))).block(WAIT);
        BatchReviewRequest request = new BatchReviewRequest();
        request.setProjectId("project");
        request.setPassed(true);
        var a = new BatchReviewRequest.Item();
        a.setId("first");
        a.setVersion(0);
        var b = new BatchReviewRequest.Item();
        b.setId("second");
        b.setVersion(0);
        request.setItems(List.of(a, b));
        assertThrows(
                BusinessException.class,
                () -> service.finalReview(request).contextWrite(employee).block(WAIT));
        assertEquals(1, problems.findById("first").block(WAIT).getAuditStage());
        assertEquals(0, problems.findById("first").block(WAIT).getVersion());
        assertEquals(0, nodes.createQuery().count().block(WAIT));
    }

    @Test
    void concurrentInitialReviewHasOneWinnerAndDoesNotCompleteTheProblem() {
        problems.insert(row("review", "external-a", 2, 0)).block(WAIT);
        ActionRequest input = new ActionRequest();
        input.setVersion(0);
        input.setPassed(true);
        List<Signal<HandoverProblemEntity>> results =
                Flux.merge(
                                service.action("review", "initial-review", input).materialize(),
                                service.action("review", "initial-review", input).materialize())
                        .contextWrite(employee)
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(1, results.stream().filter(Signal::isOnError).count());
        HandoverProblemEntity saved = problems.findById("review").block(WAIT);
        assertEquals(2, saved.getStatus());
        assertEquals(1, saved.getAuditStage());
        assertEquals(1, saved.getVersion());
        assertEquals(1, nodes.createQuery().count().block(WAIT));
    }

    @Test
    void contractorUsesAllDirectOrganizationsAndOnlyCurrentProjectSelections() {
        problems.insertBatch(
                        Mono.just(
                                List.of(
                                        row("a", "external-a", 0, 0),
                                        row("b", "external-b", 0, 0),
                                        row("c", "external-c", 0, 0))))
                .block(WAIT);
        HandoverResponsibilityEntity selected = new HandoverResponsibilityEntity();
        base(selected, "selected-a");
        selected.setOrganizationId("external-a");
        selected.setEnabled(true);
        responsibilities.insert(selected).block(WAIT);
        HandoverPersistenceFixture.directory(directory, false, List.of("external-a", "external-b"));
        HandoverQuery query = new HandoverQuery();
        query.setProjectId("project");
        query.setView("construction");
        var page = service.query(query).contextWrite(employee).block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals("a", page.getData().get(0).getId());
        assertEquals(1L, service.counts(query).contextWrite(employee).block(WAIT).get("total"));
        assertThrows(
                AccessDenyException.class,
                () -> service.read("b").contextWrite(employee).block(WAIT));
        HandoverPersistenceFixture.directory(directory, false, List.of());
        assertEquals(0, service.query(query).contextWrite(employee).block(WAIT).getTotal());
    }

    @Test
    void categoryWithChildrenCannotBecomeAChildAndDeleteReleasesSiblingName() {
        HandoverCategoryEntity root = category("根", null);
        root = configuration.save(categories, root).contextWrite(employee).block(WAIT);
        HandoverCategoryEntity child = category("子", root.getId());
        configuration.save(categories, child).contextWrite(employee).block(WAIT);
        HandoverCategoryEntity target =
                configuration
                        .save(categories, category("另一个根", null))
                        .contextWrite(employee)
                        .block(WAIT);
        root.setParentId(target.getId());
        HandoverCategoryEntity attempted = root;
        assertThrows(
                BusinessException.class,
                () -> configuration.save(categories, attempted).contextWrite(employee).block(WAIT));
        assertNull(categories.findById(root.getId()).block(WAIT).getParentId());
        assertThrows(
                BusinessException.class,
                () ->
                        configuration
                                .save(categories, category("另一个根", null))
                                .contextWrite(employee)
                                .block(WAIT));
        configuration
                .delete(categories, target.getId(), target.getVersion())
                .contextWrite(employee)
                .block(WAIT);
        assertTrue(categories.findById(target.getId()).block(WAIT).getDeleted());
        assertNotNull(
                configuration
                        .save(categories, category("另一个根", null))
                        .contextWrite(employee)
                        .block(WAIT));
    }

    @Test
    void eightStagesUnlockAfterKickoffWithoutForcingAllEarlierStagesComplete() {
        HandoverProcessEntity initial = processes.get("project").contextWrite(employee).block(WAIT);
        assertEquals(8, initial.getStages().size());
        assertThrows(
                BusinessException.class,
                () ->
                        processes
                                .action("project", "quality", "start", 0)
                                .contextWrite(employee)
                                .block(WAIT));
        HandoverProcessEntity kickoff =
                processes
                        .action("project", "kickoff", "start", 0)
                        .contextWrite(employee)
                        .block(WAIT);
        assertEquals("active", HandoverProcessService.state(kickoff, "kickoff").getStatus());
        HandoverProcessEntity quality =
                processes
                        .action("project", "quality", "start", 1)
                        .contextWrite(employee)
                        .block(WAIT);
        assertEquals("active", HandoverProcessService.state(quality, "quality").getStatus());
        assertEquals("pending", HandoverProcessService.state(quality, "material").getStatus());
        assertEquals(2, stageLogs.createQuery().count().block(WAIT));
        assertThrows(
                BusinessException.class,
                () ->
                        processes
                                .action("project", "kickoff", "start", 2)
                                .contextWrite(employee)
                                .block(WAIT));
    }

    @Test
    void simultaneousOfflineSubmissionsReturnTheSameWinner() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        ProblemRequest request = request("concurrent-offline");
        var results =
                Flux.merge(service.create(request), service.create(request))
                        .contextWrite(employee)
                        .collectList()
                        .block(WAIT);
        assertEquals(2, results.size());
        assertEquals(results.get(0).getId(), results.get(1).getId());
        assertEquals(1, problems.createQuery().count().block(WAIT));
        assertEquals(1, nodes.createQuery().count().block(WAIT));
    }

    @Test
    void confirmationAndNewProblemCannotBothWin() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        items.insert(item("racing-item")).block(WAIT);
        var request = request("racing-problem");
        request.setSource("PHYSICAL_HANDOVER");
        request.setItemId("racing-item");
        var results =
                Flux.merge(
                                service.create(request).cast(Object.class).materialize(),
                                configuration
                                        .confirm("racing-item", 0)
                                        .cast(Object.class)
                                        .materialize())
                        .contextWrite(employee)
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(1, results.stream().filter(Signal::isOnError).count());
        HandoverItemEntity item = items.findById("racing-item").block(WAIT);
        assertEquals(
                Boolean.TRUE.equals(item.getConfirmed()) ? 0 : 1,
                problems.createQuery().count().block(WAIT));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SITE_INSPECTION","PHYSICAL_HANDOVER","QUALITY_ACCEPTANCE"})
    void contractorRectificationMustPassInitialThenFinalReviewAndKeepsAuditActors(String source) {
        var cycle = row("cycle", "external-a", 0, 0);cycle.setSource(source);
        problems.insert(cycle).block(WAIT);
        var selected = new HandoverResponsibilityEntity();
        base(selected, "unit-a");
        selected.setOrganizationId("external-a");
        selected.setEnabled(true);
        responsibilities.insert(selected).block(WAIT);
        HandoverPersistenceFixture.directory(directory, false, List.of("external-a"));
        var action = new ActionRequest();
        action.setVersion(0);
        var accepted = service.action("cycle", "accept", action).contextWrite(employee).block(WAIT);
        assertEquals(1, accepted.getStatus());
        action.setVersion(1);
        action.setDescription("返工完成");
        action.setPhotos(List.of(photo("fixed", "rectification")));
        var rectified =
                service.action("cycle", "rectify", action).contextWrite(employee).block(WAIT);
        assertEquals(2, rectified.getStatus());
        assertEquals(0, rectified.getAuditStage());
        HandoverPersistenceFixture.directory(directory, true, List.of());
        action.setPhotos(List.of());
        action.setVersion(2);
        action.setPassed(true);
        var initial =
                service.action("cycle", "initial-review", action)
                        .contextWrite(employee)
                        .block(WAIT);
        assertEquals(2, initial.getStatus());
        assertEquals(1, initial.getAuditStage());
        action.setVersion(3);
        var done =
                service.action("cycle", "final-review", action).contextWrite(employee).block(WAIT);
        assertEquals(3, done.getStatus());
        assertEquals(2, done.getAuditStage());
        assertEquals(4, done.getNodes().size());
        assertEquals("rectification", done.getPhotos().get(0).getPhotoType());
        assertTrue(
                done.getNodes().stream().allMatch(node -> "tester".equals(node.getOperatorId())));
    }

    @Test
    void statisticsAggregateOnlyAuthorizedProjectAndSelectedYear() {
        var a = row("jan", "external-a", 0, 0);
        a.setCategoryId("water");
        a.setCategoryName("给排水");
        a.setInspectionTime(time(2025, 1));
        var b = row("feb", "external-a", 3, 2);
        b.setCategoryId("water");
        b.setCategoryName("给排水");
        b.setInspectionTime(time(2025, 2));
        var old = row("old", "external-a", 0, 0);
        old.setInspectionTime(time(2024, 1));
        var foreign = row("foreign", "external-a", 0, 0);
        foreign.setTenantId("other-tenant");
        foreign.setInspectionTime(time(2025, 1));
        problems.insertBatch(Mono.just(List.of(a, b, old, foreign))).block(WAIT);
        var material = new HandoverRecordEntity();
        base(material, "material");
        material.setStage("material");
        material.setTitle("资料移交");
        material.setCreateTime(time(2025, 1));
        records.insert(material).block(WAIT);
        var query = new HandoverQuery();
        query.setProjectId("project");
        var result = statistics.statistics(query, 2025).contextWrite(employee).block(WAIT);
        assertEquals(2L, result.get("total"));
        assertEquals(1L, result.get("completed"));
        assertEquals(1L, result.get("materialCount"));
        var categories = (List<HandoverStatisticsService.CategoryPoint>) result.get("byCategory");
        assertEquals(1, categories.size());
        assertEquals(2, categories.get(0).getTotal());
        var months = (List<java.util.Map<String, Object>>) result.get("monthly");
        assertEquals(12, months.size());
        assertEquals(1L, months.get(0).get("total"));
        assertEquals(1L, months.get(1).get("completed"));
        assertEquals(0L, months.get(2).get("total"));
    }

    @Test
    void inspectionRangeIncludesBothBoundsAndExcludesAdjacentValues() {
        for (long time : List.of(99L, 100L, 200L, 201L)) {
            var row = row("time-" + time, "external-a", 0, 0);
            row.setInspectionTime(time);
            problems.insert(row).block(WAIT);
        }
        var query = new HandoverQuery();
        query.setProjectId("project");
        query.setInspectionTimeFrom(100L);
        query.setInspectionTimeTo(200L);
        var page = service.query(query).contextWrite(employee).block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals(
                Set.of("time-100", "time-200"),
                page.getData().stream()
                        .map(HandoverProblemEntity::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2L, service.counts(query).contextWrite(employee).block(WAIT).get("total"));
    }

    @Test
    void editingClearsOptionalIdsAndSnapshotNamesInDatabase() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        var row = row("clear", "external-a", 0, 0);
        row.setCategoryId("old-category");
        row.setCategoryName("旧分类");
        row.setSpaceId("old-space");
        row.setSpaceName("旧空间");
        row.setOrganizationName("旧单位");
        row.setInspectionTime(123L);
        row.setInitiatorId("old-user");
        problems.insert(row).block(WAIT);
        var request = request("unused");
        request.setVersion(0);
        service.edit("clear", request).contextWrite(employee).block(WAIT);
        var saved = problems.findById("clear").block(WAIT);
        assertNull(saved.getCategoryId());
        assertNull(saved.getCategoryName());
        assertNull(saved.getSpaceId());
        assertNull(saved.getSpaceName());
        assertNull(saved.getOrganizationId());
        assertNull(saved.getOrganizationName());
        assertEquals(123L, saved.getInspectionTime());
        assertEquals("old-user", saved.getInitiatorId());
    }

    @Test
    void completedStageRejectsNewBusinessRows() {
        processes.action("project", "kickoff", "start", 0).contextWrite(employee).block(WAIT);
        var active =
                processes.action("project", "site", "start", 1).contextWrite(employee).block(WAIT);
        processes
                .action("project", "site", "complete", active.getVersion())
                .contextWrite(employee)
                .block(WAIT);
        assertThrows(
                BusinessException.class,
                () -> service.create(request("too-late")).contextWrite(employee).block(WAIT));
        assertEquals(0, problems.createQuery().count().block(WAIT));
    }

    @Test
    void responsibilityUniquenessIsScopedAndSoftDeleteReleasesIt() {
        var foreign = new HandoverResponsibilityEntity();
        base(foreign, "foreign-unit");
        foreign.setApplicationId("other-application");
        foreign.setOrganizationId("external-a");
        responsibilities.insert(foreign).block(WAIT);
        var created =
                configuration
                        .save(responsibilities, responsibility("external-a"))
                        .contextWrite(employee)
                        .block(WAIT);
        assertThrows(
                BusinessException.class,
                () ->
                        configuration
                                .save(responsibilities, responsibility("external-a"))
                                .contextWrite(employee)
                                .block(WAIT));
        configuration
                .delete(responsibilities, created.getId(), created.getVersion())
                .contextWrite(employee)
                .block(WAIT);
        assertNotNull(
                configuration
                        .save(responsibilities, responsibility("external-a"))
                        .contextWrite(employee)
                        .block(WAIT));
    }

    @Test
    void removingExternalQualificationRevokesConstructionReadAndAssignment() {
        var selected = new HandoverResponsibilityEntity();
        base(selected, "selected-a");
        selected.setOrganizationId("external-a");
        selected.setEnabled(true);
        responsibilities.insert(selected).block(WAIT);
        problems.insert(row("revoked", "external-a", 0, 0)).block(WAIT);
        HandoverPersistenceFixture.directory(directory, false, List.of("external-a"));
        when(directory.organizations("project")).thenReturn(Flux.empty());
        when(directory.external("project", "external-a"))
                .thenReturn(
                        Mono.error(
                                new BusinessException("error.handover.organization_invalid", 400)));
        var query = new HandoverQuery();
        query.setProjectId("project");
        query.setView("construction");
        assertEquals(0, service.query(query).contextWrite(employee).block(WAIT).getTotal());
        assertThrows(
                AccessDenyException.class,
                () -> service.read("revoked").contextWrite(employee).block(WAIT));
        assertThrows(
                BusinessException.class,
                () ->
                        configuration
                                .responsibility("project", "external-a")
                                .contextWrite(employee)
                                .block(WAIT));
    }

    private HandoverResponsibilityEntity responsibility(String org) {
        var row = new HandoverResponsibilityEntity();
        row.setProjectId("project");
        row.setOrganizationId(org);
        return row;
    }

    private static long time(int year, int month) {
        return java.time.LocalDate.of(year, month, 15)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli();
    }

    private static ProblemRequest request(String client) {
        var r = new ProblemRequest();
        r.setClientRequestId(client);
        r.setProjectId("project");
        r.setSource("SITE_INSPECTION");
        r.setDescription("外墙渗漏");
        return r;
    }

    private static FileRef photo(String id, String type) {
        var ref = new FileRef();
        ref.setFileId(id);
        ref.setPhotoType(type);
        return ref;
    }

    private HandoverProblemEntity row(String id, String org, int status, int audit) {
        var p = new HandoverProblemEntity();
        base(p, id);
        p.setDescription("渗漏");
        p.setSource("SITE_INSPECTION");
        p.setOrganizationId(org);
        p.setStatus(status);
        p.setAuditStage(audit);
        return p;
    }

    private HandoverItemEntity item(String id) {
        var item = new HandoverItemEntity();
        base(item, id);
        item.setSource("PHYSICAL_HANDOVER");
        item.setName("水泵");
        item.setConfirmed(false);
        return item;
    }

    private HandoverCategoryEntity category(String name, String parent) {
        var c = new HandoverCategoryEntity();
        c.setProjectId("project");
        c.setName(name);
        c.setParentId(parent);
        return c;
    }

    private void base(HandoverEntity row, String id) {
        scope.initialize(row, new HandoverContext("tenant", "application", "tester", "tester"));
        row.setId(id);
        row.setProjectId("project");
    }
}
