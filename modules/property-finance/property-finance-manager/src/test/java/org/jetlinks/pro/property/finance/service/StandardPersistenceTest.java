package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyPersistenceTest.auth;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.standard.*;
import org.jetlinks.pro.property.finance.tax.TaxItemInput;
import org.jetlinks.pro.property.finance.web.StandardController;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 真实PG验证项目边界、并发CAS/唯一性、引用保护和快照；仅项目公开来源隔离。 */
@SpringBootTest(
        classes = StandardPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StandardPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "standard_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/standard_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired StandardService service;
    @Autowired FormulaVariableService variables;
    @Autowired TaxItemService taxes;
    @Autowired InvoiceCompanyService companies;
    @Autowired ProjectCompanyService projectCompanies;
    @Autowired ProjectSubjectService projectSubjects;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<SubjectCategoryEntity, String> categories;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> companyLinks;
    @Autowired ReactiveRepository<TaxItemEntity, String> taxRows;
    @Autowired ReactiveRepository<StandardEntity, String> standardRows;
    @Autowired ReactiveRepository<FormulaVariableEntity, String> variableRows;
    @Autowired ReactiveRepository<StandardVariableReferenceEntity, String> references;
    String projectId;
    String companyId;
    String subjectId;
    String taxId;
    String categoryId;

    @BeforeEach
    void setup() {
        projectId = "p-" + UUID.randomUUID();
        companyId =
                companies
                        .create(
                                InvoiceCompanyPersistenceTest.input(
                                        "T" + UUID.randomUUID().toString().replace("-", "")))
                        .contextWrite(auth())
                        .block(WAIT)
                        .company()
                        .getId();
        projectCompanies.bind(projectId, companyId).contextWrite(auth()).block(WAIT);
        var category = new SubjectCategoryEntity();
        category.setId(UUID.randomUUID().toString());
        category.setName("目录");
        category.setNameKey(category.getId());
        category.setParentId("");
        category.setSortIndex(0);
        initialize(category);
        categories.insert(category).block(WAIT);
        categoryId = category.getId();
        var subject = new SubjectEntity();
        subject.setId(UUID.randomUUID().toString());
        subject.setCategoryId(categoryId);
        subject.setCode("KM" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        subject.setName("科目");
        subject.setNameKey(subject.getId());
        subject.setFeeType("1");
        subject.setArrearsConfigId("fixture-arrears");
        subject.setEnabled(true);
        subject.setAutoInvoice(false);
        initialize(subject);
        subjects.insert(subject).block(WAIT);
        subjectId = subject.getId();
        projectSubjects
                .issue(new ProjectSubjectService.IssueInput(List.of(subjectId), List.of(projectId)))
                .contextWrite(auth())
                .block(WAIT);
        var tax = new TaxItemInput();
        tax.setCompanyId(companyId);
        tax.setGoodsName("物业服务");
        tax.setGoodsCode("301");
        tax.setClassificationName("服务");
        tax.setTaxRate(new BigDecimal("0.06"));
        taxId = taxes.create(projectId, tax).contextWrite(auth()).block(WAIT).getId();
    }

    @Test
    void saveFreezesTaxVariablesAndLargePriceWithoutRemoteSyncGate() {
        var variable =
                variables
                        .create(
                                projectId,
                                new VariableInput("FLOOR_FACTOR", "楼层系数", null, null, true, null))
                        .contextWrite(auth())
                        .block(WAIT);
        var input = input("S1", "快照");
        input.setPrice("999999999999999999.99");
        input.setFormulaExpression("${STANDARD_PRICE}*${FLOOR_FACTOR}");
        input.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[楼层系数]");
        var saved = service.create(projectId, input).contextWrite(auth()).block(WAIT);
        assertEquals("999999999999999999.99", saved.getPrice().toPlainString());
        assertEquals("物业服务", saved.getTaxSnapshot().get("goodsName"));
        assertEquals(companyId, saved.getTaxSnapshot().get("companyId"));
        variables
                .update(
                        projectId,
                        variable.id(),
                        new VariableInput("FLOOR_FACTOR", "新名称", null, 100, false, 0L))
                .contextWrite(auth())
                .block(WAIT);
        taxRows.createUpdate().set("goodsName", "已修改税目").where("id", taxId).execute().block(WAIT);
        var detail = service.detail(projectId, saved.getId()).contextWrite(auth()).block(WAIT);
        assertEquals(
                "楼层系数",
                detail.getVariablesSnapshot().stream()
                        .filter(v -> v.variableCode().equals("FLOOR_FACTOR"))
                        .findFirst()
                        .orElseThrow()
                        .variableName());
        assertEquals("物业服务", detail.getTaxSnapshot().get("goodsName"));
        input.setVersion(0L);
        conflict(service.update(projectId, saved.getId(), input), 400);
        conflict(variables.delete(projectId, variable.id(), 1L), 409);
        assertEquals(
                1, references.createQuery().where("standardId", saved.getId()).count().block(WAIT));
    }

    @Test
    void builtinMeterStandardsSaveAndReopenWithoutProjectVariables() {
        var input = input("W1", "阶梯水费");
        input.setBillingMethod("WATER"); input.setSettlementMethod("MONTHLY"); input.setPrice("3.00");
        input.setFormulaExpression("TIERED(${STANDARD_PRICE}*${WATER_USAGE},10,4,20,5)*0.9+2");
        input.setFormulaDisplay("[费用_金额]=阶梯累加([收费标准_单价]*[用水量],10,4,20,5)*0.9+2");
        var saved = service.create(projectId,input).contextWrite(auth()).block(WAIT);
        var detail = service.detail(projectId,saved.getId()).contextWrite(auth()).block(WAIT);
        assertEquals(input.getFormulaExpression(),detail.getFormulaExpression());
        assertTrue(detail.getVariablesSnapshot().stream().anyMatch(v -> v.variableCode().equals("WATER_USAGE") && v.scopeType().equals("SYSTEM")));
        StepVerifier.create(variables.create(projectId,new VariableInput("WATER_USAGE","伪造用水量",null,null,true,null)).contextWrite(auth()))
                .expectErrorMatches(failure -> TaxItemPersistenceTest.status(failure,400)).verify(WAIT);
        var electricity = input("E1","固定电费"); electricity.setBillingMethod("ELECTRICITY");
        electricity.setFormulaExpression("${STANDARD_PRICE}*${ELECTRICITY_USAGE}");
        electricity.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[用电量]");
        assertEquals("ELECTRICITY",service.create(projectId,electricity).contextWrite(auth()).block(WAIT).getBillingMethod());
    }

    @Test
    void existingProjectUsageIdentitySurvivesNewSystemRegistration() {
        var legacy = new FormulaVariableEntity();
        legacy.setId(UUID.randomUUID().toString());
        legacy.setProjectId(projectId);
        legacy.setVariableCode("WATER_USAGE");
        legacy.setVariableName("原用水量");
        legacy.setNameKey("原用水量");
        legacy.setEnabled(true);
        legacy.setSort(10);
        initialize(legacy);
        variableRows.insert(legacy).block(WAIT);

        var matches = variables.list(projectId).contextWrite(auth()).collectList().block(WAIT)
                .stream().filter(row -> row.variableCode().equals("WATER_USAGE")).toList();
        assertEquals(1, matches.size());
        assertEquals(legacy.getId(), matches.get(0).id());
        assertEquals("PROJECT", matches.get(0).scopeType());
        var updated = variables.update(projectId, legacy.getId(),
                new VariableInput("WATER_USAGE", "原用水量", "更新说明", 10, true, 0L))
                .contextWrite(auth()).block(WAIT);
        assertEquals(legacy.getId(), updated.id());
        assertEquals(1L, updated.version());
    }

    @Test
    void concurrentUniquenessAndCasAreEnforcedByActualPersistence() {
        var first = input("S1", "标准");
        var duplicate = input("s1", "标准");
        var results =
                Flux.merge(
                                service.create(projectId, first).materialize(),
                                service.create(projectId, duplicate).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                results.stream()
                        .filter(signal -> TaxItemPersistenceTest.status(signal.getThrowable(), 409))
                        .count());
        var saved = results.stream().filter(Signal::isOnNext).findFirst().orElseThrow().get();
        conflict(service.create(projectId, input("S2", saved.getStandardName())), 409);
        first.setVersion(0L);
        duplicate.setVersion(0L);
        first.setStandardName("更新甲");
        duplicate.setStandardName("更新乙");
        results =
                Flux.merge(
                                service.update(projectId, saved.getId(), first).materialize(),
                                service.update(projectId, saved.getId(), duplicate).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                results.stream()
                        .filter(signal -> TaxItemPersistenceTest.status(signal.getThrowable(), 409))
                        .count());
        assertEquals(1, standardRows.findById(saved.getId()).block(WAIT).getVersion());
        conflict(service.delete(projectId, saved.getId(), 0L), 409);
        var current = service.detail(projectId, saved.getId()).contextWrite(auth()).block(WAIT);
        service.delete(projectId, saved.getId(), 1L).contextWrite(auth()).block(WAIT);
        var recreated =
                service.create(projectId, input(current.getStandardCode(), "重新建档"))
                        .contextWrite(auth())
                        .block(WAIT);
        assertNotEquals(current.getStandardCode(), recreated.getStandardCode());
        assertTrue(standardRows.findById(saved.getId()).block(WAIT).getDeleted());
    }

    @Test
    void concurrentCreationGeneratesIndependentCodesAndEditingWithoutCodePreservesIdentity() {
        var inputs = List.of(input("USER-CODE", "自动编码甲"), input("USER-CODE", "自动编码乙"));
        var rows =
                Flux.fromIterable(inputs)
                        .flatMap(input -> service.create(projectId, input))
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(2, rows.size());
        assertEquals(2, rows.stream().map(StandardView::getStandardCode).distinct().count());
        for (var row : rows) {
            assertTrue(row.getStandardCode().matches("BZ[A-F0-9]{16}"));
            assertEquals(
                    row.getStandardCode().toLowerCase(Locale.ROOT),
                    standardRows.findById(row.getId()).block(WAIT).getCodeKey());
        }
        var row = rows.get(0);
        // 老数据的人工编码同样保留，普通编辑不能重新生成或清空。
        standardRows
                .createUpdate()
                .set("standardCode", "OLD-001")
                .set("codeKey", "old-001")
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var edit = input(null, row.getStandardName());
        edit.setVersion(row.getVersion());
        var saved = service.update(projectId, row.getId(), edit).contextWrite(auth()).block(WAIT);
        assertEquals("OLD-001", saved.getStandardCode());
        assertEquals("old-001", standardRows.findById(row.getId()).block(WAIT).getCodeKey());
    }

    @Test
    void projectIsolationIssuedSubjectTaxAndDateFilteringCannotBeBypassedWithOrTerms() {
        var input = input("S1", "日期边界");
        input.setExecuteDate("2026-01-01");
        input.setCancelDate("2026-12-31");
        var saved = service.create(projectId, input).contextWrite(auth()).block(WAIT);
        var query = new QueryParamEntity();
        query.or("projectId", "eq", projectId);
        assertEquals(
                0,
                service.query("other", query, null, null, null, null)
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        conflict(service.detail("other", saved.getId()), 404);
        conflict(service.create("forbidden", input), 403);
        conflict(service.create("other", input), 403);
        for (var pair :
                Map.of(
                                "2025-12-31",
                                "NOT_STARTED",
                                "2026-01-01",
                                "EFFECTIVE",
                                "2026-12-31",
                                "EFFECTIVE",
                                "2027-01-01",
                                "EXPIRED")
                        .entrySet()) {
            var page =
                    service.query(
                                    projectId,
                                    new QueryParamEntity(),
                                    categoryId,
                                    subjectId,
                                    pair.getValue(),
                                    LocalDate.parse(pair.getKey()))
                            .contextWrite(auth())
                            .block(WAIT);
            assertEquals(1, page.getTotal(), pair.toString());
            assertEquals(pair.getValue(), page.getData().get(0).getEffectiveStatus());
        }
        subjects.createUpdate().set("enabled", false).where("id", subjectId).execute().block(WAIT);
        conflict(service.create(projectId, input("S2", "无效科目")), 409);
        StepVerifier.create(service.detail(projectId, saved.getId()))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void actualReferencesGuardTaxCompanyUnbindAndIssuedSubjectDeletion() {
        var saved = service.create(projectId, input("S1", "引用标准")).contextWrite(auth()).block(WAIT);
        conflict(taxes.state(projectId, taxId, 0L, false), 409);
        conflict(taxes.delete(projectId, taxId, 0L), 409);
        var relation =
                issued.createQuery()
                        .where("projectId", projectId)
                        .and("subjectId", subjectId)
                        .fetchOne()
                        .block(WAIT);
        conflict(projectSubjects.delete(projectId, relation.getId(), 0), 409);
        var other =
                companies
                        .create(
                                InvoiceCompanyPersistenceTest.input(
                                        "T" + UUID.randomUUID().toString().replace("-", "")))
                        .contextWrite(auth())
                        .block(WAIT)
                        .company();
        var second =
                projectCompanies
                        .bind(projectId, other.getId())
                        .contextWrite(auth())
                        .block(WAIT)
                        .relation();
        projectCompanies.setDefault(projectId, second.getId(), 0).contextWrite(auth()).block(WAIT);
        var companyLink =
                companyLinks
                        .createQuery()
                        .where("projectId", projectId)
                        .and("companyId", companyId)
                        .fetchOne()
                        .block(WAIT);
        conflict(
                projectCompanies.unbind(projectId, companyLink.getId(), companyLink.getVersion()),
                409);
        // 隔离其它主档引用，证明标准引用本身保护公司，失败不会回写删除标志。
        companyLinks
                .createUpdate()
                .set("deleted", true)
                .setNull("defaultKey")
                .where("companyId", companyId)
                .execute()
                .block(WAIT);
        taxRows.createUpdate().set("deleted", true).where("id", taxId).execute().block(WAIT);
        conflict(companies.delete(companyId, 0), 409);
        service.delete(projectId, saved.getId(), 0L).contextWrite(auth()).block(WAIT);
        assertTrue(standardRows.findById(saved.getId()).block(WAIT).getDeleted());
        conflict(service.create(projectId, input("S1", "编码保留")), 403);
    }

    @Test
    void fullOptionsAndTaxPickerHaveRealTotalsAndExportPreservesDecimalText() throws Exception {
        var saved = service.create(projectId, input("S1", "导出")).contextWrite(auth()).block(WAIT);
        var rows = new ArrayList<SubjectEntity>();
        var relations = new ArrayList<ProjectSubjectEntity>();
        var taxOptions = new ArrayList<TaxItemEntity>();
        var subjectTemplate = subjects.findById(subjectId).block(WAIT);
        var taxTemplate = taxRows.findById(taxId).block(WAIT);
        for (int i = 0; i < 205; i++) {
            var subject = new SubjectEntity();
            org.springframework.beans.BeanUtils.copyProperties(subjectTemplate, subject);
            subject.setId(UUID.randomUUID().toString());
            subject.setName("科目" + i);
            subject.setCode("KM" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            subject.setNameKey(subject.getId());
            rows.add(subject);
            var relation = new ProjectSubjectEntity();
            relation.setId(UUID.randomUUID().toString());
            relation.setProjectId(projectId);
            relation.setSubjectId(subject.getId());
            initialize(relation);
            relations.add(relation);
            var tax = new TaxItemEntity();
            org.springframework.beans.BeanUtils.copyProperties(taxTemplate, tax);
            tax.setId(UUID.randomUUID().toString());
            tax.setMatchKey(tax.getId());
            tax.setGoodsName(String.format("选项%03d", i));
            tax.setEnabled(i != 204);
            taxOptions.add(tax);
        }
        subjects.insertBatch(Flux.just(rows)).block(WAIT);
        issued.insertBatch(Flux.just(relations)).block(WAIT);
        taxRows.insertBatch(Flux.just(taxOptions)).block(WAIT);
        var options = service.options(projectId).contextWrite(auth()).block(WAIT);
        assertEquals(206, ((List<?>) options.get("subjects")).size());
        var definitions = (List<VariableDefinition>) options.get("variables");
        assertEquals(8, definitions.size());
        assertTrue(definitions.stream().anyMatch(row -> row.variableCode().equals("WATER_USAGE")));
        assertTrue(definitions.stream().anyMatch(row -> row.variableCode().equals("ELECTRICITY_USAGE")));
        var query = new QueryParamEntity();
        query.setPageSize(1);
        var firstPage = service.taxOptions(projectId, query).contextWrite(auth()).block(WAIT);
        assertEquals(205, firstPage.getTotal());
        assertEquals(1, firstPage.getData().size());
        query.setPageIndex(1);
        var nextPage = service.taxOptions(projectId, query).contextWrite(auth()).block(WAIT);
        assertEquals(205, nextPage.getTotal());
        assertNotEquals(firstPage.getData().get(0).getId(), nextPage.getData().get(0).getId());
        var bytes =
                service.export(projectId, query, categoryId, null, null, null)
                        .contextWrite(auth())
                        .block(WAIT);
        try (var workbook =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(
                    saved.getStandardCode(),
                    workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("面积", workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
            assertEquals("分", workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
            assertEquals("按月", workbook.getSheetAt(0).getRow(1).getCell(8).getStringCellValue());
            assertEquals("否", workbook.getSheetAt(0).getRow(1).getCell(15).getStringCellValue());
            assertEquals(
                    saved.getPrice().toPlainString(),
                    workbook.getSheetAt(0).getRow(1).getCell(5).getStringCellValue());
        }
    }

    @Test
    void variableDefinitionsProtectSystemNamesProjectBoundaryAndRealReferences() {
        conflict(
                variables.create(
                        projectId,
                        new VariableInput("STANDARD_PRICE", "伪造单价", null, null, true, null)),
                400);
        conflict(
                variables.create(
                        projectId,
                        new VariableInput("OTHER_PRICE", "收费标准_单价", null, null, true, null)),
                400);
        var input = new VariableInput("FACTOR", "项目系数", null, 10, true, null);
        var signals =
                Flux.merge(
                                variables.create(projectId, input).materialize(),
                                variables.create(projectId, input).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, signals.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                signals.stream()
                        .filter(s -> TaxItemPersistenceTest.status(s.getThrowable(), 409))
                        .count());
        var variable = signals.stream().filter(Signal::isOnNext).findFirst().orElseThrow().get();
        conflict(
                variables.update(
                        "other",
                        variable.id(),
                        new VariableInput("FACTOR", "新系数", null, 10, true, 0L)),
                404);
        var rule = input("CUSTOM", "变量引用");
        rule.setFormulaExpression("${STANDARD_PRICE}*${FACTOR}");
        rule.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[项目系数]");
        var saved = service.create(projectId, rule).contextWrite(auth()).block(WAIT);
        conflict(variables.delete(projectId, variable.id(), 0L), 409);
        variables
                .update(
                        projectId,
                        variable.id(),
                        new VariableInput("NEW_FACTOR", "新系数", null, 10, true, 0L))
                .contextWrite(auth())
                .block(WAIT);
        var historical = service.detail(projectId, saved.getId()).contextWrite(auth()).block(WAIT);
        var historicalVariable =
                historical.getVariablesSnapshot().stream()
                        .filter(v -> v.id().equals(variable.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("FACTOR", historicalVariable.variableCode());
        assertEquals("项目系数", historicalVariable.variableName());
        rule.setVersion(0L);
        conflict(service.update(projectId, saved.getId(), rule), 400);
        rule.setFormulaExpression("${STANDARD_PRICE}*${NEW_FACTOR}");
        rule.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[新系数]");
        var changed =
                service.update(projectId, saved.getId(), rule).contextWrite(auth()).block(WAIT);
        assertEquals(1L, changed.getFormulaVersion());
        assertEquals(
                "NEW_FACTOR",
                changed.getVariablesSnapshot().stream()
                        .filter(v -> v.id().equals(variable.id()))
                        .findFirst()
                        .orElseThrow()
                        .variableCode());
        assertEquals("FACTOR", historicalVariable.variableCode());
        conflict(variables.delete(projectId, variable.id(), 1L), 409);
        service.delete(projectId, saved.getId(), 1L).contextWrite(auth()).block(WAIT);
        variables.delete(projectId, variable.id(), 1L).contextWrite(auth()).block(WAIT);
        assertTrue(
                variables.list(projectId).contextWrite(auth()).collectList().block(WAIT).stream()
                        .noneMatch(v -> v.id().equals(variable.id())));
    }

    @Test
    void concurrentTaxDisableAndStandardSaveNeverLeaveAnActiveInvalidReference() {
        var results =
                Flux.merge(
                                service.create(projectId, input("RACE", "并发保护"))
                                        .materialize()
                                        .map(signal -> signal),
                                taxes.state(projectId, taxId, 0L, false)
                                        .materialize()
                                        .map(signal -> signal))
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                results.stream()
                        .filter(s -> TaxItemPersistenceTest.status(s.getThrowable(), 409))
                        .count());
        int count =
                standardRows
                        .createQuery()
                        .where("projectId", projectId)
                        .and("deleted", false)
                        .count()
                        .block(WAIT);
        assertTrue(count == 0 || taxRows.findById(taxId).block(WAIT).getEnabled());
    }

    @Test
    void httpContractReturnsDecimalStringRejectsMissingVersionAndDoesNotExposeStoredJson() {
        var client =
                WebTestClient.bindToController(new StandardController(service, variables))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(auth()))
                        .build();
        var input = input(null, "HTTP标准");
        input.setPrice("999999999999999999.99");
        var saved =
                client.post()
                        .uri(base())
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.price")
                        .isEqualTo("999999999999999999.99")
                        .jsonPath("$.variablesSnapshot[0].variableCode")
                        .isEqualTo("STANDARD_PRICE")
                        .jsonPath("$.variablesSnapshotJson")
                        .doesNotExist()
                        .returnResult();
        String id =
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(
                                new String(
                                        saved.getResponseBody(),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .getString("id");
        client.put()
                .uri(base() + "/" + id)
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        var originalCode =
                service.detail(projectId, id).contextWrite(auth()).block(WAIT).getStandardCode();
        assertTrue(originalCode.matches("BZ[A-F0-9]{16}"));
        input.setVersion(0L);
        input.setStandardCode("FORGED-CODE");
        client.put()
                .uri(base() + "/" + id)
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.standardCode")
                .isEqualTo(originalCode);
        assertEquals(
                originalCode.toLowerCase(Locale.ROOT),
                standardRows.findById(id).block(WAIT).getCodeKey());
        client.delete().uri(base() + "/" + id).exchange().expectStatus().isBadRequest();
        client.post()
                .uri(base() + "/formula/_validate")
                .bodyValue(
                        new StandardController.FormulaInput(
                                "${STANDARD_PRICE}*2", "[费用_金额]=[收费标准_单价]*3"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    StandardInput input(String code, String name) {
        var input = new StandardInput();
        input.setStandardCode(code);
        input.setStandardName(name);
        input.setSubjectId(subjectId);
        input.setCompanyId(companyId);
        input.setTaxItemId(taxId);
        input.setBillingMethod("AREA");
        input.setFormulaExpression("${STANDARD_PRICE}*${BILLING_AREA}*${PERIOD_MONTHS}");
        input.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[计费面积]*[起止时间（月）]");
        input.setPrice("1.23");
        input.setChargeRoundType(2);
        input.setMantissa(0);
        input.setSettlementMethod("MONTHLY");
        input.setIsDelay(0);
        return input;
    }

    String base() {
        return "/property/finance/projects/" + projectId + "/standards";
    }

    static void initialize(FinanceEntity entity) {
        org.jetlinks.pro.property.finance.payment.FinanceDocumentNumbers.assign(entity);
        entity.setDeleted(false);
        entity.setVersion(0L);
        entity.setCreateTime(System.currentTimeMillis());
    }

    static void conflict(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(auth()))
                .expectErrorMatches(failure -> TaxItemPersistenceTest.status(failure, status))
                .verify(WAIT);
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import({
        InvoiceCompanyService.class,
        ProjectCompanyService.class,
        TaxItemService.class,
        SubjectService.class,
        SubjectCategoryService.class,
        ArrearsService.class,
        SubjectDictionaryService.class,
        ProjectSubjectService.class,
        FormulaVariableService.class,
        StandardService.class,
        FinancialPeriodGate.class,
        FinancialPeriodTestSupport.class,
        ChargeFormulaParser.class
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
            return new TaxItemPersistenceTest.Config().scope();
        }
    }
}
