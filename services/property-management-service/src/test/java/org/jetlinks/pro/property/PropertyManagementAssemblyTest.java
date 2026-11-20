package org.jetlinks.pro.property;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 统一发布必须装入八个业务模块，并保留原路径的鉴权及二维码公开边界。 */
class PropertyManagementAssemblyTest {

    @Test
    void everyEntityGetsItsOwnRepositoryIncludingBothBusinessStandards() throws Exception {
        var registry = new DefaultListableBeanFactory();
        new org.hswebframework.web.crud.configuration.EasyormRepositoryRegistrar()
                .registerBeanDefinitions(org.springframework.core.type.AnnotationMetadata
                        .introspect(PropertyManagementApplication.class), registry);
        for (var entity : List.of(
                "org.jetlinks.pro.property.finance.entity.StandardEntity",
                "org.jetlinks.pro.property.facility.entity.FacilityStandardEntity")) {
            var type = Class.forName(entity);
            assertTrue(java.util.Arrays.stream(registry.getBeanDefinitionNames())
                    .map(registry::getBeanDefinition)
                    .anyMatch(definition -> type.equals(definition.getPropertyValues().get("entityType"))), entity);
        }
    }

    @Test
    void oneEntryPointLoadsEveryBusinessModuleWithoutBeanNameCollisions() throws Exception {
        Class.forName("org.jetlinks.pro.property.PropertyManagementApplication");
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(registry);
        assertDoesNotThrow(() -> scanner.scan(
                "org.jetlinks.pro.property", "org.jetlinks.pro.patrol", "org.jetlinks.pro.smartpark"));
        for (var name : List.of("propertyOrganizationService", "houseSettingService",
                "decorationApplicationService", "patrolPointController", "repairOrderController",
                "qualityTaskController", "facilityTaskController", "handoverProblemController")) {
            assertTrue(registry.containsBeanDefinition(name), name);
        }
        for (var legacy : List.of("org.jetlinks.pro.property.base.PropertyBaseApplication",
                "org.jetlinks.pro.property.finance.PropertyFinanceApplication",
                "org.jetlinks.pro.patrol.PatrolApplication")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(legacy));
        }
    }

    @Test
    void businessRoutesRemainProtectedAndPublicQrRouteKeepsPrecedence() throws Exception {
        try (var input = new ClassPathResource("route/property-management.json").getInputStream()) {
            var rows = new ObjectMapper().readTree(input);
            var routes = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            rows.forEach(row -> assertNull(routes.put(row.path("path").asText(), row), "duplicate route"));
            for (var path : List.of("/property/**", "/property/finance/**",
                    "/property/facility-energy/**", "/property/handover/**", "/park/patrol/**",
                    "/repair/**", "/quality/**", "/decoration/**")) {
                assertTrue(routes.containsKey(path), path);
                assertTrue(routes.get(path).path("authorize").asBoolean(), path);
            }
            var publicQr = routes.get("/repair/public/qr/**");
            assertNotNull(publicQr);
            assertFalse(publicQr.path("authorize").asBoolean());
            assertTrue(publicQr.path("order").asInt() < routes.get("/repair/**").path("order").asInt());
            var parser = new PathPatternParser();
            assertTrue(parser.parse("/property/facility-energy/**")
                    .matches(PathContainer.parsePath("/property/facility-energy/tasks/_query")));
            for (var domain : List.of("finance", "facility-energy", "handover")) {
                assertTrue(routes.get("/property/" + domain + "/**").path("order").asInt()
                        < routes.get("/property/**").path("order").asInt());
            }
        }
    }
}
