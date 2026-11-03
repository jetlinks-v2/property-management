package org.jetlinks.pro.property.base.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.basic.define.DefaultBasicAuthorizeDefinition;
import org.jetlinks.pro.test.web.TestAuthentication;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PropertyUserControllerTest {
    @Test
    void everyRouteRequiresMatchingMenuActionAndNoGenericDeletionExists() {
        for (var method : PropertyUserController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            String action =
                    method.getName().equals("synchronize")
                            ? "sync"
                            : method.getName().equals("identities")
                                            && method.getParameterCount() > 0
                                    ? "save"
                                    : "query";
            var definition =
                    DefaultBasicAuthorizeDefinition.from(PropertyUserController.class, method);
            assertTrue(
                    definition
                            .getResources()
                            .hasPermission(
                                    new TestAuthentication("operator")
                                            .addPermission("property-user", action)));
            assertFalse(
                    definition.getResources().hasPermission(new TestAuthentication("ungranted")));
            assertFalse(
                    definition
                            .getResources()
                            .hasPermission(
                                    new TestAuthentication("saas-user")
                                            .addPermission("user", action)));
            assertNull(
                    method.getAnnotation(
                            org.springframework.web.bind.annotation.DeleteMapping.class));
        }
        assertEquals(Object.class, PropertyUserController.class.getSuperclass());
    }

    @Test
    void invalidOrMissingIdentitiesCannotClearButEmptyCollectionCan() throws Exception {
        var mapper = new ObjectMapper();
        var type = PropertyUserController.IdentityUpdate.class;
        assertThrows(
                Exception.class,
                () -> mapper.readValue("{\"identities\":[\"admin\"],\"version\":0}", type));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var json :
                    Set.of(
                            "{\"version\":0}",
                            "{\"identities\":null,\"version\":0}",
                            "{\"identities\":[],\"version\":-1}",
                            "{\"identities\":[null],\"version\":0}",
                            "{\"identities\":[]}"))
                assertFalse(validator.validate(mapper.readValue(json, type)).isEmpty(), json);
            assertTrue(
                    validator
                            .validate(mapper.readValue("{\"identities\":[],\"version\":0}", type))
                            .isEmpty());
            assertEquals(
                    2,
                    mapper.readValue(
                                    "{\"identities\":[\"owner\",\"employee\"],\"version\":0}", type)
                            .identities()
                            .size());
        }
    }
}
