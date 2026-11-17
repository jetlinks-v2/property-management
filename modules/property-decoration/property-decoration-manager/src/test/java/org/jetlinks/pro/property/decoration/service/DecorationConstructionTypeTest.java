package org.jetlinks.pro.property.decoration.service;

import jakarta.validation.Validation;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecorationConstructionTypeTest {
    private DecorationRequests.Create request(int type) {
        var input = new DecorationRequests.Create();
        input.setRequestId("request"); input.setProjectId("project");
        input.setSpaceId("house"); input.setCustomerId("owner");
        input.setContactPhone("13800000000"); input.setOwnerCertificate("11010519491231002X");
        input.setConstructionType(type); input.setDecorationType("first");
        input.setStartTime(1L); input.setCompletionTime(2L); input.setNoticesAccepted(true);
        return input;
    }

    @Test void selfDecorationAcceptsMissingContractorButStillValidatesOwner() {
        var input = request(2);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(input).isEmpty());
            assertDoesNotThrow(() -> DecorationRules.applicationContacts(input));
            input.setContactPhone("invalid");
            assertFalse(factory.getValidator().validate(input).isEmpty());
            assertEquals("error.decoration.invalid_owner_phone", assertThrows(BusinessException.class,
                () -> DecorationRules.applicationContacts(input)).getMessage());
            input.setContactPhone("13800000000"); input.setOwnerCertificate("");
            assertFalse(factory.getValidator().validate(input).isEmpty());
            assertEquals("error.decoration.invalid_owner_identity", assertThrows(BusinessException.class,
                () -> DecorationRules.applicationContacts(input)).getMessage());
        }
    }

    @Test void companyRequiresCompleteAndValidContractorInformation() {
        var input = request(1);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(input).isEmpty());
            assertEquals("error.decoration.contractor_required", assertThrows(BusinessException.class,
                () -> DecorationRules.applicationContacts(input)).getMessage());
            input.setConstructionUnit("装修公司"); input.setConstructionManager("负责人");
            input.setManagerPhone("13900000000"); input.setManagerCertificate("11010519491231002X");
            assertTrue(validator.validate(input).isEmpty());
            assertDoesNotThrow(() -> DecorationRules.applicationContacts(input));
            input.setManagerPhone("invalid");
            assertFalse(validator.validate(input).isEmpty());
            assertEquals("error.decoration.invalid_manager_phone", assertThrows(BusinessException.class,
                () -> DecorationRules.applicationContacts(input)).getMessage());
            input.setManagerPhone("13900000000"); input.setManagerCertificate("invalid");
            assertFalse(validator.validate(input).isEmpty());
            assertEquals("error.decoration.invalid_manager_identity", assertThrows(BusinessException.class,
                () -> DecorationRules.applicationContacts(input)).getMessage());
        }
    }
}
