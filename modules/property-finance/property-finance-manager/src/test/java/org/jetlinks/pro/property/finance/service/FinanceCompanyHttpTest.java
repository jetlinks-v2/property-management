package org.jetlinks.pro.property.finance.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.property.finance.entity.FinanceCompanyEntity;
import org.jetlinks.pro.property.finance.web.InvoiceCompanyController;
import org.jetlinks.pro.property.finance.web.ProjectCompanyController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** HTTP 解析和路由契约；授权与真实事务分别由项目命令和持久化行为测试覆盖。 */
class FinanceCompanyHttpTest {
    private final InvoiceCompanyService companies = mock(InvoiceCompanyService.class);
    private final ProjectCompanyService projects = mock(ProjectCompanyService.class);
    private final WebTestClient client =
            WebTestClient.bindToController(
                            new InvoiceCompanyController(companies, projects),
                            new ProjectCompanyController(projects))
                    .controllerAdvice(new Errors())
                    .build();

    @Test
    void companyCreatePreservesTaxNumberAndBankFieldsAndDoesNotExposeGenericSaveRoutes() {
        var entity = new FinanceCompanyEntity();
        entity.setId("company");
        entity.setCompanyName("项目开票公司");
        when(companies.create(any()))
                .thenReturn(Mono.just(new InvoiceCompanyService.Detail(entity, List.of())));
        client.post()
                .uri("/property/finance/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "companyName",
                                "项目开票公司",
                                "taxNumber",
                                "TAX01",
                                "banks",
                                List.of(
                                        Map.of(
                                                "id",
                                                "bank",
                                                "bankName",
                                                "入账银行",
                                                "bankAccount",
                                                "123",
                                                "defaultBank",
                                                true,
                                                "jointLineNumber",
                                                "102"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.company.id")
                .isEqualTo("company");
        verify(companies)
                .create(
                        argThat(
                                input ->
                                        "TAX01".equals(input.getTaxNumber())
                                                && input.getBanks().size() == 1
                                                && "102"
                                                        .equals(
                                                                input.getBanks()
                                                                        .get(0)
                                                                        .jointLineNumber())));
        client.patch()
                .uri("/property/finance/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("deleted", true))
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }

    @Test
    void defaultAndDeleteRequireExplicitVersionsAndKeepProjectInPath() {
        when(projects.setDefault("project-a", "link", 2)).thenReturn(Mono.empty());
        client.put()
                .uri("/property/finance/projects/project-a/companies/link/default")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", 2))
                .exchange()
                .expectStatus()
                .isOk();
        verify(projects).setDefault("project-a", "link", 2);
        client.put()
                .uri("/property/finance/projects/project-a/companies/link/default")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isBadRequest();
        verify(projects, never()).setDefault("project-a", "link", 0);
        client.delete()
                .uri("/property/finance/companies/company")
                .exchange()
                .expectStatus()
                .isBadRequest();
        verify(companies, never()).delete(anyString(), anyLong());
    }

    @Test
    void projectDetailUsesProjectBoundaryRatherThanSharedMaintenanceEndpoint() {
        var entity = new FinanceCompanyEntity();
        entity.setId("company");
        when(projects.detail("project-a", "link"))
                .thenReturn(Mono.just(new InvoiceCompanyService.Detail(entity, List.of())));
        client.get()
                .uri("/property/finance/projects/project-a/companies/link")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.company.id")
                .isEqualTo("company");
        verify(projects).detail("project-a", "link");
        verifyNoInteractions(companies);
    }

    /** 保持业务异常的HTTP状态契约；不模拟平台登录、权限拦截或全局响应包装。 */
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class Errors {
        @org.springframework.web.bind.annotation.ExceptionHandler(
                org.hswebframework.web.exception.BusinessException.class)
        org.springframework.http.ResponseEntity<Map<String, String>> business(
                org.hswebframework.web.exception.BusinessException error) {
            return org.springframework.http.ResponseEntity.status(error.getStatus())
                    .body(Map.of("code", error.getMessage()));
        }
    }
}
