package org.jetlinks.pro.property.base.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.hswebframework.web.authorization.basic.define.DefaultBasicAuthorizeDefinition;
import org.jetlinks.pro.test.web.TestAuthentication;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertyOrganizationControllerTest {
    @Test
    void eachEndpointRequiresItsOwnMenuAction() throws Exception {
        for (String method :
                List.of("tree", "detail", "types", "status", "updateType", "synchronize")) {
            var signature =
                    switch (method) {
                        case "detail" -> new Class<?>[] {String.class};
                        case "updateType" ->
                                new Class<?>[] {
                                    String.class, PropertyOrganizationController.TypeUpdate.class
                                };
                        default -> new Class<?>[0];
                    };
            String action =
                    method.equals("updateType")
                            ? "save"
                            : method.equals("synchronize") ? "sync" : "query";
            var definition =
                    DefaultBasicAuthorizeDefinition.from(
                            PropertyOrganizationController.class,
                            PropertyOrganizationController.class.getMethod(method, signature));
            assertTrue(
                    definition
                            .getResources()
                            .hasPermission(
                                    new TestAuthentication("operator")
                                            .addPermission("property-organization", action)));
            assertFalse(
                    definition.getResources().hasPermission(new TestAuthentication("ungranted")));
            assertFalse(
                    definition
                            .getResources()
                            .hasPermission(
                                    new TestAuthentication("other-module")
                                            .addPermission("organization", action)));
        }
    }

    @Test
    void missingTypeAndInvalidEnumAreRejectedButExplicitNullClears() throws Exception {
        var mapper = new ObjectMapper();
        var type = PropertyOrganizationController.TypeUpdate.class;
        assertThrows(Exception.class, () -> mapper.readValue("{\"version\":0}", type));
        assertThrows(
                Exception.class,
                () -> mapper.readValue("{\"organizationType\":\"invalid\",\"version\":0}", type));
        assertNull(
                mapper.readValue("{\"organizationType\":null,\"version\":0}", type)
                        .organizationType());
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            assertFalse(
                    validator
                            .getValidator()
                            .validate(
                                    mapper.readValue(
                                            "{\"organizationType\":null,\"version\":-1}", type))
                            .isEmpty());
            assertFalse(
                    validator
                            .getValidator()
                            .validate(mapper.readValue("{\"organizationType\":null}", type))
                            .isEmpty());
        }
    }
}
