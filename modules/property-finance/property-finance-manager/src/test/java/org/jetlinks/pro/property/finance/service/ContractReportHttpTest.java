package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.jetlinks.pro.property.finance.command.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.web.ContractReportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.util.context.Context;

import java.io.ByteArrayInputStream;
import java.util.*;

/** 真实控制器、PG及文件内容；本地注入认证不代表经过实际SaaS安全过滤链。 */
@SpringBootTest(
        classes = ContractReportHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractReportHttpTest extends ContractReportPersistenceTest {
    static final String ROOT = "/property/finance/reports/contracts";
    @Autowired ContractReportController controller;
    @Autowired FinanceContractReportCommandSupport commands;
    @Autowired ContractReportExportService exports;

    WebTestClient client(Context context) {
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(context))
                .build();
    }

    @Test
    void queryAndDrilldownUseExactMoneyAndNeverExposeInternalSources() {
        var c = contract("C01");
        bill("C01", "9999999999999999.99", "2024-02-01", "2024-02-29");
        var http = client(allowed);
        http.post()
                .uri(ROOT + "/_query")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary.currentReceivable")
                .isEqualTo("9999999999999999.99")
                .jsonPath("$.page.data[0].code")
                .isEqualTo("C01")
                .jsonPath("$.page.data[0].tenantId")
                .doesNotExist()
                .jsonPath("$.page.data[0].fingerprint")
                .doesNotExist();
        http.post()
                .uri(ROOT + "/" + c.getId() + "/currentReceivable/_details")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].amount")
                .isEqualTo("9999999999999999.99")
                .jsonPath("$.data[0].snapshotJson")
                .doesNotExist();
        http.post()
                .uri(ROOT + "/" + c.getId() + "/sql/_details")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.post()
                .uri(ROOT + "/_query")
                .bodyValue(Map.of("projectId", project, "month", "2024-13"))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.delete().uri(ROOT + "/" + c.getId()).exchange().expectStatus().isNotFound();
    }

    @Test
    void excelAndPdfContainChineseHeadersExactAmountsSummaryAndSafeLiteralText() throws Exception {
        var c = contract("C01");
        contracts
                .createUpdate()
                .set("name", "=SUM(1,2)<script>evil</script>")
                .where("id", c.getId())
                .execute()
                .block(WAIT);
        var b = bill("C01", "100.01", "2024-02-01", "2024-02-29");
        var bytes =
                client(allowed)
                        .post()
                        .uri(ROOT + "/_export/excel")
                        .bodyValue(input("2024-02"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectHeader()
                        .valueMatches("Content-Disposition", ".*attachment.*")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var s = book.getSheetAt(0);
            assertEquals("合同账单报表", s.getSheetName());
            assertEquals("合同编号", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    s.getRow(1).getCell(1).getCellType());
            assertEquals(
                    "=SUM(1,2)<script>evil</script>", s.getRow(1).getCell(1).getStringCellValue());
            assertEquals("100.01", s.getRow(1).getCell(9).getStringCellValue());
            assertEquals("100.01", s.getRow(2).getCell(9).getStringCellValue());
            assertTrue(s.getRow(3).getCell(0).getStringCellValue().contains("2024-02"));
        }
        var pdf =
                client(allowed)
                        .post()
                        .uri(ROOT + "/_export/pdf")
                        .bodyValue(input("2024-02"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType("application/pdf")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var text =
                    java.text.Normalizer.normalize(
                            new org.apache.pdfbox.text.PDFTextStripper().getText(doc),
                            java.text.Normalizer.Form.NFKC);
            assertTrue(text.contains("合同账单报表"), text);
            assertTrue(text.contains("100.01"), text);
            assertTrue(
                    text.replaceAll("\\s+", "").contains("=SUM(1,2)<script>evil</script>"), text);
            javax.imageio.ImageIO.write(
                    new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 110),
                    "png",
                    java.nio.file.Path.of("target", "contract-report-preview.png").toFile());
        }
        assertEquals(b.getVersion(), bills.findById(b.getId()).block(WAIT).getVersion());
        client(allowed)
                .post()
                .uri(ROOT + "/_export/html")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void commandAndHttpKeepCallerAndIndependentQueryExportPermissions() {
        var c = contract("C01");
        bill("C01", "10", "2024-02-01", "2024-02-29");
        var command = new QueryFinanceContractReportCommand();
        command.setQuery(input("2024-02"));
        assertEquals(
                "10.00",
                commands.queryReport(command)
                        .contextWrite(allowed)
                        .block(WAIT)
                        .summary()
                        .currentReceivable());
        var detail = new QueryFinanceContractReportDetailsCommand();
        detail.setQuery(input("2024-02"));
        detail.setContractId(c.getId());
        detail.setMetric("currentReceivable");
        assertEquals(
                "10.00",
                commands.queryDetails(detail)
                        .contextWrite(allowed)
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .amount());
        var auth = assets.auth(reader);
        auth.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-report-contract")
                                .actions(Set.of("query"))
                                .build()));
        var queryOnly = Context.of(Authentication.class, auth);
        client(queryOnly)
                .post()
                .uri(ROOT + "/_query")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isOk();
        client(queryOnly)
                .post()
                .uri(ROOT + "/_export/excel")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isForbidden();
        var noButton = Context.of(Authentication.class, assets.auth(reader));
        reactor.test.StepVerifier.create(commands.queryReport(command).contextWrite(noButton))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        client(noButton)
                .post()
                .uri(ROOT + "/_query")
                .bodyValue(input("2024-02"))
                .exchange()
                .expectStatus()
                .isForbidden();
        command.getQuery().setProjectId("forbidden");
        rejects(commands.queryReport(command), 403);
    }

    @Configuration
    @Import({
        ContractReportPersistenceTest.Config.class,
        ContractReportController.class,
        ContractReportExportService.class,
        ContractReportFile.class,
        FinanceContractReportCommandSupport.class
    })
    static class Config {}

    @Test
    void platformCommandAdapterDispatchesTypedContractsAndPreservesCaller() {
        var c = contract("C01");
        bill("C01", "100", "2024-02-01", "2024-02-29");
        var adapter = org.jetlinks.supports.command.JavaBeanCommandSupport.create(commands);
        var query = new QueryFinanceContractReportCommand();
        query.setQuery(input("2024-02"));
        assertTrue(adapter.commandIsSupported(query).block(WAIT));
        assertEquals(
                "100.00",
                adapter.execute(query)
                        .contextWrite(allowed)
                        .block(WAIT)
                        .summary()
                        .currentReceivable());
        var detail = new QueryFinanceContractReportDetailsCommand();
        detail.setQuery(input("2024-02"));
        detail.setContractId(c.getId());
        detail.setMetric("currentReceivable");
        assertTrue(adapter.commandIsSupported(detail).block(WAIT));
        assertEquals(
                "100.00",
                adapter.execute(detail)
                        .contextWrite(allowed)
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .amount());
        reactor.test.StepVerifier.create(
                        adapter.execute(query)
                                .contextWrite(
                                        Context.of(Authentication.class, assets.auth(reader))))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }
}
