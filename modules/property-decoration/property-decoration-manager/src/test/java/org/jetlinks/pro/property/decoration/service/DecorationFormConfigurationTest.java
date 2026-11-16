package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import reactor.test.StepVerifier;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 两类表单配置共享本次项目权限校验，缺配置、停用或租户错误仍明确阻止办理。 */
class DecorationFormConfigurationTest {
    private final DecorationScope scope = mock(DecorationScope.class);
    private final DecorationConfigurationRepository repository = mock(DecorationConfigurationRepository.class);
    private final DecorationAttachmentService attachments = mock(DecorationAttachmentService.class);
    private final DecorationConfigurationService service = new DecorationConfigurationService(scope, repository, attachments);
    private final DecorationScope.Context ctx = new DecorationScope.Context("tenant", "app", "user", "用户", null);

    private DecorationConfigurationEntity config(String kind) {
        var row = new DecorationConfigurationEntity(); row.setTenantId("tenant"); row.setEnabled(true); row.setDeleted(false);
        var content = new DecorationModels.Configuration();
        if ("agreement".equals(kind)) content.setAgreement(new DecorationModels.Agreement());
        else { var material = new DecorationModels.MaterialDefinition(); material.setId("material"); content.setMaterials(List.of(material)); }
        row.setContent(content); return row;
    }

    private void stub(DecorationConfigurationEntity agreement, Mono<DecorationConfigurationEntity> materials) {
        var project = new PropertyOrganizationInfo(); project.setName("演示园区");
        when(scope.project("project")).thenReturn(Mono.just(project));
        when(scope.dictionaryItems("house-type")).thenReturn(Flux.just(new PropertyDictionaryItemInfo("residential", "住宅", 1L)));
        when(repository.findById(DecorationConfigurationService.key("app", "project", "agreement", "residential", "", "")))
            .thenReturn(Mono.just(agreement));
        when(repository.findById(DecorationConfigurationService.key("app", "project", "materials", "residential", "first", "")))
            .thenReturn(materials);
    }

    @Test void bothConfigurationsMatchExactDimensionsWithOneProjectAuthorization() {
        var agreement = config("agreement"); var materials = config("materials");
        materials.getContent().setSelfInspectionTemplateFileId("self-template");
        stub(agreement, Mono.just(materials));
        StepVerifier.create(service.form(ctx, "project", "residential", "first"))
            .assertNext(result -> { assertSame(agreement.getContent().getAgreement(), result.agreement());
                assertEquals("material", result.materials().get(0).getId());
                assertEquals("self-template", result.selfInspectionTemplateFileId()); }).verifyComplete();
        verify(scope, times(1)).project("project");
        verify(scope, never()).dictionaryItems(anyString());
    }

    @Test void missingConfigurationIdentifiesItsKindAndMatchingDimensions() {
        stub(config("agreement"), Mono.empty());
        StepVerifier.create(service.form(ctx, "project", "residential", "first"))
            .expectErrorSatisfies(error -> {
                var business = (BusinessException) error;
                assertEquals("error.decoration.configuration_missing_materials", business.getCode());
                assertEquals("演示园区", business.getArgs()[0]);
                assertEquals("住宅", business.getArgs()[1]);
                assertFalse(business.getArgs()[2].toString().isBlank());
            }).verify();
        when(repository.findById(DecorationConfigurationService.key("app", "project", "agreement", "residential", "", "")))
            .thenReturn(Mono.empty());
        StepVerifier.create(service.match(ctx, "project", "agreement", "residential", "", ""))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.configuration_missing_agreement", ((BusinessException) error).getCode())).verify();
        when(repository.findById(DecorationConfigurationService.key("app", "project", "checks", "residential", "second", "secondAcceptance")))
            .thenReturn(Mono.empty());
        StepVerifier.create(service.match(ctx, "project", "checks", "residential", "second", "secondAcceptance"))
            .expectErrorSatisfies(error -> {
                var business = (BusinessException) error;
                assertEquals("error.decoration.configuration_missing_checks", business.getCode());
                assertFalse(business.getArgs()[3].toString().isBlank());
            }).verify();
    }

    @Test void missingHouseTypePointsToThePropertyRecordInsteadOfSuggestingAnEmptyConfiguration() {
        stub(config("agreement"), Mono.empty());
        when(repository.findById(DecorationConfigurationService.key("app", "project", "agreement", "", "", "")))
            .thenReturn(Mono.empty());
        StepVerifier.create(service.match(ctx, "project", "agreement", "", "", ""))
            .expectErrorSatisfies(error -> {
                var business = (BusinessException) error;
                assertEquals("error.decoration.house_type_missing", business.getCode());
                assertEquals("演示园区", business.getArgs()[0]);
            }).verify();
    }

    @Test void legacyConfigurationReturnsEmptyTemplateReferenceWithoutUsingAnOldStaticTemplate() {
        stub(config("agreement"), Mono.just(config("materials")));
        StepVerifier.create(service.form(ctx, "project", "residential", "first"))
            .assertNext(result -> assertEquals("", result.selfInspectionTemplateFileId())).verifyComplete();
    }

    @Test void selfInspectionTemplateMustPassTheExistingProjectTemplateAttachmentAuthorizationBeforeSaving() {
        var input = new DecorationRequests.Config(); input.setKind("materials"); input.setProjectId("project");
        input.setHouseType("residential"); input.setDecorationType("first");
        var content = new DecorationModels.Configuration(); content.setSelfInspectionTemplateFileId("self-template"); input.setContent(content);
        when(scope.current()).thenReturn(Mono.just(ctx)); when(scope.employee()).thenReturn(Mono.empty());
        when(scope.project("project")).thenReturn(Mono.empty());
        when(attachments.validate(List.of("self-template"), "project", null, ctx, true)).thenReturn(Mono.error(new AccessDenyException()));
        StepVerifier.create(service.save(input)).expectError(AccessDenyException.class).verify();
        verify(attachments).validate(List.of("self-template"), "project", null, ctx, true);
        verifyNoInteractions(repository);
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
