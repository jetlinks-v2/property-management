package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.subject.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.*;

/** 真实 PostgreSQL 验证事务、并发、引用、逻辑删除及项目隔离；字典/项目公开边界单独隔离。 */
@SpringBootTest(
        classes = SubjectPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubjectPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "subject_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/subject_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired SubjectCategoryService categories;
    @Autowired SubjectService subjects;
    @Autowired ArrearsService arrears;
    @Autowired SubjectExcelService excel;
    @Autowired ProjectSubjectService projects;
    @Autowired ReactiveRepository<SubjectEntity, String> subjectRows;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> projectRows;
    @Autowired ReactiveRepository<SubjectCategoryEntity, String> categoryRows;
    @Autowired ReactiveRepository<ArrearsConfigEntity, String> arrearsRows;
    @Autowired ReactiveRepository<ArrearsRuleEntity, String> ruleRows;
    @Autowired ReactiveRepository<SubjectImportEntity, String> imports;
    @Autowired io.r2dbc.spi.ConnectionFactory connectionFactory;
    String categoryId;
    String arrearsId;

    @BeforeEach
    void setup() {
        imports.deleteById(imports.createQuery().fetch().map(SubjectImportEntity::getId))
                .block(WAIT);
        projectRows
                .deleteById(projectRows.createQuery().fetch().map(ProjectSubjectEntity::getId))
                .block(WAIT);
        subjectRows
                .deleteById(subjectRows.createQuery().fetch().map(SubjectEntity::getId))
                .block(WAIT);
        categoryRows
                .deleteById(categoryRows.createQuery().fetch().map(SubjectCategoryEntity::getId))
                .block(WAIT);
        ruleRows.deleteById(ruleRows.createQuery().fetch().map(ArrearsRuleEntity::getId))
                .block(WAIT);
        arrearsRows
                .deleteById(arrearsRows.createQuery().fetch().map(ArrearsConfigEntity::getId))
                .block(WAIT);
        categoryId =
                categories
                        .create(new SubjectCategoryInput(null, null, "费用", 0))
                        .contextWrite(auth())
                        .block(WAIT)
                        .getId();
        arrearsId =
                arrears.create(
                                arrearsInput(
                                        null, "催缴", List.of(new ArrearsInput.Rule(null, 30, "1"))))
                        .contextWrite(auth())
                        .block(WAIT)
                        .config()
                        .getId();
    }

    @Test
    void arbitraryDepthDescendantFilterAndReferencedCategoryDeleteProtection() {
        String parent = categoryId;
        for (int depth = 0; depth < 6; depth++)
            parent =
                    categories
                            .create(new SubjectCategoryInput(null, parent, "子目录" + depth, depth))
                            .contextWrite(auth())
                            .block(WAIT)
                            .getId();
        var input = input("深层物业费");
        input.setCategoryId(parent);
        var row = subjects.create(input).contextWrite(auth()).block(WAIT);
        assertEquals(
                1,
                subjects.query(new QueryParamEntity(), categoryId)
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(categories.delete(categoryId, 0).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        var other =
                categories
                        .create(new SubjectCategoryInput(null, null, "其他", 0))
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(
                0,
                subjects.query(new QueryParamEntity(), other.getId())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        assertEquals(parent, row.getCategoryId());
    }

    @Test
    void concurrentCodesAreUniqueImmutableAndDuplicateNamesAreRejected() {
        var values =
                Flux.range(0, 12)
                        .flatMap(index -> subjects.create(input("科目" + index)), 6)
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(12, values.stream().map(SubjectEntity::getCode).distinct().count());
        assertTrue(values.stream().allMatch(row -> row.getCode().matches("KM[A-F0-9]{16}")));
        var first = values.get(0);
        var change = input("改名");
        change.setVersion(0L);
        var updated = subjects.update(first.getId(), change).contextWrite(auth()).block(WAIT);
        assertEquals(first.getCode(), updated.getCode());
        StepVerifier.create(subjects.update(first.getId(), change).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        StepVerifier.create(subjects.create(input("改名")).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(12, subjectRows.createQuery().count().block(WAIT));
    }

    @Test
    void repeatedConcurrentIssueIsIdempotentProjectIsolationAndSharedDeleteIsProtected() {
        var subject = subjects.create(input("物业费")).contextWrite(auth()).block(WAIT);
        var issue =
                new ProjectSubjectService.IssueInput(
                        List.of(subject.getId(), subject.getId()), List.of("a", "a", "b"));
        var results =
                Flux.merge(projects.issue(issue), projects.issue(issue))
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(
                2, results.stream().mapToLong(ProjectSubjectService.IssueResult::created).sum());
        assertEquals(2, projectRows.createQuery().count().block(WAIT));
        assertEquals(
                1,
                projects.query("a", new QueryParamEntity(), null)
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                0,
                projects.query("c", new QueryParamEntity(), null)
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        var relation = projectRows.createQuery().where("projectId", "a").fetchOne().block(WAIT);
        StepVerifier.create(projects.detail("b", relation.getId()).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        StepVerifier.create(subjects.delete(subject.getId(), 0).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        projects.delete("a", relation.getId(), 0).contextWrite(auth()).block(WAIT);
        assertTrue(projectRows.findById(relation.getId()).block(WAIT).getDeleted());
        projects.issue(new ProjectSubjectService.IssueInput(List.of(subject.getId()), List.of("a")))
                .contextWrite(auth())
                .block(WAIT);
        assertFalse(projectRows.findById(relation.getId()).block(WAIT).getDeleted());
        assertEquals(2L, projectRows.findById(relation.getId()).block(WAIT).getVersion());
        StepVerifier.create(projects.delete("a", relation.getId(), 0).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
    }

    @Test
    void unauthorizedIssueRollsBackAllAndLogicalDeleteCannotBeQueriedByOrTerms() {
        var subject = subjects.create(input("租赁费")).contextWrite(auth()).block(WAIT);
        StepVerifier.create(
                        projects.issue(
                                        new ProjectSubjectService.IssueInput(
                                                List.of(subject.getId()),
                                                List.of("a", "forbidden")))
                                .contextWrite(auth()))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 403)
                .verify(WAIT);
        assertEquals(0, projectRows.createQuery().count().block(WAIT));
        subjects.delete(subject.getId(), 0).contextWrite(auth()).block(WAIT);
        assertTrue(subjectRows.findById(subject.getId()).block(WAIT).getDeleted());
        var query = new QueryParamEntity();
        query.or("deleted", "eq", true);
        assertEquals(0, subjects.query(query, null).contextWrite(auth()).block(WAIT).getTotal());
        StepVerifier.create(subjects.detail(subject.getId()).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
    }

    @Test
    void arrearsListIncludesOnlyCurrentRulesAndKeepsPagination() {
        arrears.update(
                        arrearsId,
                        arrearsInput(
                                0L,
                                "催缴",
                                List.of(
                                        new ArrearsInput.Rule(null, 90, "2"),
                                        new ArrearsInput.Rule(null, 10, "1"))))
                .contextWrite(auth())
                .block(WAIT);
        var other =
                arrears.create(
                                arrearsInput(
                                        null, "其他通知", List.of(new ArrearsInput.Rule(null, 5, "2"))))
                        .contextWrite(auth())
                        .block(WAIT);
        var query = new QueryParamEntity();
        query.setPageSize(1);
        query.orderBy("id");
        var seen = new HashSet<String>();
        for (int index = 0; index < 2; index++) {
            query.setPageIndex(index);
            var page = arrears.query(query).contextWrite(auth()).block(WAIT);
            assertEquals(2, page.getTotal());
            assertEquals(index, page.getPageIndex());
            assertEquals(1, page.getData().size());
            var row = page.getData().get(0);
            assertTrue(seen.add(row.getId()));
            assertEquals(row.getId(), row.getRules().get(0).getConfigId());
            assertEquals(
                    row.getId().equals(arrearsId) ? List.of(10, 90) : List.of(5),
                    row.getRules().stream().map(ArrearsRuleEntity::getOverdueDays).toList());
            assertTrue(row.getRules().stream().noneMatch(ArrearsRuleEntity::getDeleted));
            assertEquals(
                    row.getId().equals(arrearsId) ? List.of("1", "2") : List.of("2"),
                    row.getRules().stream().map(ArrearsRuleEntity::getLevelCode).toList());
        }
        arrears.delete(other.config().getId(), 0).contextWrite(auth()).block(WAIT);
        query.setPageIndex(0);
        var remaining = arrears.query(query).contextWrite(auth()).block(WAIT);
        assertEquals(1, remaining.getTotal());
        assertEquals(arrearsId, remaining.getData().get(0).getId());
        query.toQuery().and("name", "不存在的配置");
        assertTrue(arrears.query(query).contextWrite(auth()).block(WAIT).getData().isEmpty());
    }

    @Test
    void arrearsRulesAreSortedValidatedVersionedAndReferencedDeletionRejected() {
        var input =
                arrearsInput(
                        0L,
                        "催缴",
                        List.of(
                                new ArrearsInput.Rule(null, 90, "2"),
                                new ArrearsInput.Rule(null, 10, "1")));
        var updated = arrears.update(arrearsId, input).contextWrite(auth()).block(WAIT);
        assertEquals(
                List.of(10, 90),
                updated.rules().stream().map(ArrearsRuleEntity::getOverdueDays).toList());
        assertEquals(1L, updated.config().getVersion());
        StepVerifier.create(arrears.update(arrearsId, input).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        StepVerifier.create(
                        arrears.create(
                                        arrearsInput(
                                                null,
                                                "错误",
                                                List.of(
                                                        new ArrearsInput.Rule(null, 1, "1"),
                                                        new ArrearsInput.Rule(null, 1, "2"))))
                                .contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        subjects.create(input("停车费")).contextWrite(auth()).block(WAIT);
        StepVerifier.create(arrears.delete(arrearsId, 1).contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(1, arrearsRows.createQuery().count().block(WAIT));
        assertEquals(3, ruleRows.createQuery().count().block(WAIT));
    }

    @Test
    void excelValidatesFinalRowBeforeAnyWriteAndLateDuplicateRollsBackEntireBatch()
            throws Exception {
        var template = excel.template().contextWrite(auth()).block(WAIT);
        try (var workbook =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(template))) {
            assertEquals(7, workbook.getSheetAt(0).getRow(0).getLastCellNum());
            assertEquals(5, workbook.getSheetAt(0).getDataValidations().size());
        }
        StepVerifier.create(excel.importFile(file(workbook("第一行", "", false))).contextWrite(auth()))
                .expectErrorMatches(
                        error ->
                                error instanceof BusinessException e
                                        && ((Number) e.getArgs()[0]).intValue() == 3)
                .verify(WAIT);
        assertEquals(0, subjectRows.createQuery().count().block(WAIT));
        subjects.create(input("已有")).contextWrite(auth()).block(WAIT);
        StepVerifier.create(
                        excel.importFile(file(workbook("整批回滚", "已有", false))).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        assertEquals(1, subjectRows.createQuery().count().block(WAIT));
        StepVerifier.create(
                        excel.importFile(file(workbook("公式", "第二条", true))).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        assertEquals(1, subjectRows.createQuery().count().block(WAIT));
        assertEquals(
                2,
                excel.importFile(file(workbook("成功一", "成功二", false)))
                        .contextWrite(auth())
                        .block(WAIT)
                        .count());
        assertEquals(3, subjectRows.createQuery().count().block(WAIT));
        var history = excel.records(new QueryParamEntity()).contextWrite(auth()).block(WAIT);
        assertEquals(4, history.getTotal());
        var failed =
                history.getData().stream().filter(row -> row.getStatus().equals("FAILED")).toList();
        assertEquals(3, failed.size());
        assertTrue(failed.stream().allMatch(row -> row.getSuccessCount() == 0));
        assertTrue(failed.stream().allMatch(row -> row.getTotalCount() == 2));
        assertTrue(
                history.getData().stream()
                        .allMatch(
                                row ->
                                        "subjects.xlsx".equals(row.getFileName())
                                                && "subject-tester".equals(row.getModifierId())
                                                && row.getFinishTime() >= row.getCreateTime()));
        assertEquals(
                2,
                history.getData().stream()
                        .filter(row -> row.getStatus().equals("SUCCESS"))
                        .findFirst()
                        .orElseThrow()
                        .getSuccessCount());
    }

    @Test
    void batchLastVersionOrReferenceFailureRollsBackEarlierDeletes() {
        var a = subjects.create(input("批量甲")).contextWrite(auth()).block(WAIT);
        var b = subjects.create(input("批量乙")).contextWrite(auth()).block(WAIT);
        var ordered =
                java.util.stream.Stream.of(a, b)
                        .sorted(Comparator.comparing(SubjectEntity::getId))
                        .toList();
        var first = ordered.get(0);
        var last = ordered.get(1);
        StepVerifier.create(
                        subjects.deleteBatch(
                                        List.of(
                                                new SubjectService.VersionedId(first.getId(), 0L),
                                                new SubjectService.VersionedId(last.getId(), 99L)))
                                .contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        assertFalse(subjectRows.findById(first.getId()).block(WAIT).getDeleted());
        assertEquals(0L, subjectRows.findById(first.getId()).block(WAIT).getVersion());
        projects.issue(new ProjectSubjectService.IssueInput(List.of(last.getId()), List.of("a")))
                .contextWrite(auth())
                .block(WAIT);
        StepVerifier.create(
                        subjects.deleteBatch(
                                        List.of(
                                                new SubjectService.VersionedId(first.getId(), 0L),
                                                new SubjectService.VersionedId(last.getId(), 0L)))
                                .contextWrite(auth()))
                .expectErrorMatches(SubjectPersistenceTest::conflict)
                .verify(WAIT);
        assertFalse(subjectRows.findById(first.getId()).block(WAIT).getDeleted());
        var relation = projectRows.createQuery().fetchOne().block(WAIT);
        projects.delete("a", relation.getId(), 0).contextWrite(auth()).block(WAIT);
        subjects.deleteBatch(
                        List.of(
                                new SubjectService.VersionedId(last.getId(), 0L),
                                new SubjectService.VersionedId(first.getId(), 0L)))
                .contextWrite(auth())
                .block(WAIT);
        assertTrue(subjectRows.findById(first.getId()).block(WAIT).getDeleted());
        assertTrue(subjectRows.findById(last.getId()).block(WAIT).getDeleted());
        assertEquals(
                0,
                subjects.query(new QueryParamEntity(), null)
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void databaseFailureAfterFirstImportedRowRollsBackRowsButRetainsFailureBatch()
            throws Exception {
        sql(
                "CREATE FUNCTION subject_import_fault() RETURNS trigger AS $$ BEGIN IF NEW.name ="
                    + " '事务故障' THEN RAISE EXCEPTION 'forced persistence failure'; END IF; RETURN"
                    + " NEW; END; $$ LANGUAGE plpgsql");
        sql(
                "CREATE TRIGGER subject_import_fault BEFORE INSERT ON pm_fin_subject FOR EACH ROW"
                    + " EXECUTE FUNCTION subject_import_fault()");
        try {
            StepVerifier.create(
                            excel.importFile(file(workbook("必须回滚", "事务故障", false)))
                                    .contextWrite(auth()))
                    .expectErrorMatches(
                            error ->
                                    error instanceof BusinessException e
                                            && ((Number) e.getArgs()[0]).intValue() == 3)
                    .verify(WAIT);
            assertEquals(0, subjectRows.createQuery().count().block(WAIT));
            var record = imports.createQuery().fetchOne().block(WAIT);
            assertEquals("FAILED", record.getStatus());
            assertEquals(2, record.getTotalCount());
            assertEquals(0, record.getSuccessCount());
            assertNotNull(record.getErrorMessage());
            assertNotNull(record.getFinishTime());
        } finally {
            sql("DROP TRIGGER subject_import_fault ON pm_fin_subject");
            sql("DROP FUNCTION subject_import_fault()");
        }
    }

    void sql(String statement) {
        Mono.usingWhen(
                        Mono.from(connectionFactory.create()),
                        connection ->
                                Flux.from(connection.createStatement(statement).execute())
                                        .flatMap(io.r2dbc.spi.Result::getRowsUpdated)
                                        .then(),
                        connection -> Mono.from(connection.close()))
                .block(WAIT);
    }

    byte[] workbook(String first, String second, boolean formula) throws Exception {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var output = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("收费科目");
            var headers = List.of("收费目录完整路径", "收费科目名称", "费用类型", "是否生效", "是否自动开票", "欠费通知等级", "备注");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++)
                header.createCell(index).setCellValue(headers.get(index));
            int number = 1;
            for (var name : List.of(first, second)) {
                var row = sheet.createRow(number++);
                var values = List.of("费用", name, "1", "是", "否", "催缴", "");
                for (int index = 0; index < values.size(); index++)
                    row.createCell(index).setCellValue(values.get(index));
            }
            if (formula) sheet.getRow(1).getCell(1).setCellFormula("1+1");
            book.write(output);
            return output.toByteArray();
        }
    }

    static org.springframework.http.codec.multipart.FilePart file(byte[] bytes) {
        var file =
                org.mockito.Mockito.mock(org.springframework.http.codec.multipart.FilePart.class);
        org.mockito.Mockito.when(file.filename()).thenReturn("subjects.xlsx");
        org.mockito.Mockito.when(file.content())
                .thenAnswer(
                        invocation ->
                                Flux.just(
                                        new org.springframework.core.io.buffer
                                                        .DefaultDataBufferFactory()
                                                .wrap(bytes)));
        return file;
    }

    SubjectInput input(String name) {
        var value = new SubjectInput();
        value.setCategoryId(categoryId);
        value.setName(name);
        value.setFeeType("1");
        value.setEnabled(true);
        value.setAutoInvoice(false);
        value.setArrearsConfigId(arrearsId);
        return value;
    }

    static ArrearsInput arrearsInput(Long version, String name, List<ArrearsInput.Rule> rules) {
        return new ArrearsInput(version, name, null, rules);
    }

    static boolean conflict(Throwable failure) {
        return failure instanceof BusinessException e && e.getStatus() == 409;
    }

    static Context auth() {
        var user = new SimpleUser();
        user.setId("subject-tester");
        user.setUsername("科目测试");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(Authentication.class, auth);
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import({
        SubjectService.class,
        SubjectCategoryService.class,
        ProjectSubjectService.class,
        ArrearsService.class,
        SubjectExcelService.class
    })
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    static class Config {
        @org.springframework.context.annotation.Bean
        FinanceScope scope() {
            return new FinanceScope() {
                @Override
                public Mono<org.jetlinks.pro.property.base.organization.PropertyProjectInfo>
                        project(
                                String id,
                                org.jetlinks.pro.assets.crud.CrudAssetPermission permission) {
                    return authenticated()
                            .flatMap(
                                    auth ->
                                            "forbidden".equals(id)
                                                    ? Mono.error(error("project_forbidden", 403))
                                                    : Mono.just(
                                                            new org.jetlinks.pro.property.base
                                                                    .organization
                                                                    .PropertyProjectInfo(id, id)));
                }
            };
        }

        @org.springframework.context.annotation.Bean
        SubjectDictionaryService dictionaries() {
            return new SubjectDictionaryService() {
                @Override
                public Mono<List<PropertyDictionaryOption>> options(String typeCode) {
                    return Mono.just(
                            List.of(
                                    new PropertyDictionaryOption("1", "等级一"),
                                    new PropertyDictionaryOption("2", "等级二")));
                }
            };
        }
    }
}
