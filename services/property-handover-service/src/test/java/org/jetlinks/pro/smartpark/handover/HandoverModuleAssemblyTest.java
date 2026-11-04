package org.jetlinks.pro.smartpark.handover;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 独立运行类路径、认证路由与服务配置的装配门禁；不冒充外部SaaS联调。 */
class HandoverModuleAssemblyTest {
    @Test
    void handoverScansWithoutOtherBusinessManagers() {
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(registry);
        assertDoesNotThrow(
                () ->
                        scanner.scan(
                                "org.jetlinks.pro.property",
                                "org.jetlinks.pro.smartpark.handover"));
        assertTrue(registry.containsBeanDefinition("handoverProblemController"));
        assertTrue(registry.containsBeanDefinition("handoverDirectory"));
        assertTrue(registry.containsBeanDefinition("handoverExportService"));
        for (String implementation :
                new String[] {
                    "org.jetlinks.pro.property.base.service.PropertyOrganizationService",
                    "org.jetlinks.pro.smartpark.repair.service.RepairOrderService",
                    "org.jetlinks.pro.patrol.service.PatrolTaskService"
                }) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(implementation));
        }
        assertTrue(SmartParkHandoverApplication.class.isAnnotationPresent(EnableScheduling.class));
    }

    @Test
    void bothRoutesRequirePlatformAuthentication() throws Exception {
        try (var stream = new ClassPathResource("route/property-handover.json").getInputStream()) {
            var routes = new ObjectMapper().readTree(stream);
            assertEquals(2, routes.size());
            assertEquals("/property/handover/**", routes.get(0).path("path").asText());
            assertEquals(
                    "/v3/api-docs/smart-park-handover-service",
                    routes.get(1).path("path").asText());
            for (var route : routes) assertTrue(route.path("authorize").asBoolean());
        }
    }

    @Test
    void serviceConfigurationKeepsDiscoveryJwtAndDedicatedDatabaseAtCorrectPaths() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertEquals("true", properties.getProperty("jetlinks.microservice.discovery.enabled"));
        assertEquals(
                "${JWT_PUBLIC_KEY}",
                properties.getProperty("jetlinks.microservice.token.jwt.decode-key"));
        assertEquals("${SERVER_PORT:8507}", properties.getProperty("server.port"));
        assertTrue(
                properties
                        .getProperty("spring.r2dbc.url")
                        .contains("${DB_DATABASE:property_handover}"));
        assertNull(properties.getProperty("jetlinks.tenant.microservice"));
    }
}
