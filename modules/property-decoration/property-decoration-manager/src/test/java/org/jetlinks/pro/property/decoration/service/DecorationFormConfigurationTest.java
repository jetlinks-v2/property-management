package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 两类表单配置共享本次项目权限校验，缺配置、停用或租户错误仍明确阻止办理。 */
class DecorationFormConfigurationTest {
    private final DecorationScope scope = mock(DecorationScope.class);
    private final DecorationConfigurationRepository repository = mock(DecorationConfigurationRepository.class);
    private final DecorationConfigurationService service = new DecorationConfigurationService(scope, repository, mock(DecorationAttachmentService.class));
    private final DecorationScope.Context ctx = new DecorationScope.Context("tenant", "app", "user", "用户", null);

    private DecorationConfigurationEntity config(String kind) {
        var row = new DecorationConfigurationEntity(); row.setTenantId("tenant"); row.setEnabled(true); row.setDeleted(false);
        var content = new DecorationModels.Configuration();
        if ("agreement".equals(kind)) content.setAgreement(new DecorationModels.Agreement());
        else { var material = new DecorationModels.MaterialDefinition(); material.setId("material"); content.setMaterials(List.of(material)); }
        row.setContent(content); return row;
    }

    private void stub(DecorationConfigurationEntity agreement, Mono<DecorationConfigurationEntity> materials) {
        when(scope.project("project")).thenReturn(Mono.just(new PropertyOrganizationInfo()));
        when(repository.findById(DecorationConfigurationService.key("app", "project", "agreement", "residential", "", "")))
            .thenReturn(Mono.just(agreement));
        when(repository.findById(DecorationConfigurationService.key("app", "project", "materials", "residential", "first", "")))
            .thenReturn(materials);
    }

    @Test void bothConfigurationsMatchExactDimensionsWithOneProjectAuthorization() {
        var agreement = config("agreement"); var materials = config("materials"); stub(agreement, Mono.just(materials));
        StepVerifier.create(service.form(ctx, "project", "residential", "first"))
            .assertNext(result -> { assertSame(agreement.getContent().getAgreement(), result.agreement());
                assertEquals("material", result.materials().get(0).getId()); }).verifyComplete();
        verify(scope, times(1)).project("project");
    }

    @Test void deniedProjectDoesNotReadConfiguration() {
        when(scope.project("project")).thenReturn(Mono.error(new AccessDenyException()));
        StepVerifier.create(service.form(ctx, "project", "residential", "first")).expectError(AccessDenyException.class).verify();
        verifyNoInteractions(repository);
    }

    @Test void missingDisabledAndForeignTenantConfigurationsDoNotBecomeEmptyForm() {
        for (String scenario : List.of("missing", "disabled", "foreign", "deleted")) {
            var materials = config("materials");
            if ("disabled".equals(scenario)) materials.setEnabled(false);
            if ("foreign".equals(scenario)) materials.setTenantId("other");
            if ("deleted".equals(scenario)) materials.setDeleted(true);
            stub(config("agreement"), "missing".equals(scenario) ? Mono.empty() : Mono.just(materials));
            StepVerifier.create(service.form(ctx, "project", "residential", "first"))
                .expectErrorSatisfies(e -> assertEquals(409, ((BusinessException) e).getStatus())).verify();
        }
    }
}
