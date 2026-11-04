package org.jetlinks.pro.smartpark.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

/** 验证报修服务独立装配且保留原权限配置，不依赖本机业务数据库。 */
class RepairModuleAssemblyTest {
    @Test
    void scansRepairDomainWithoutSiblingBusinessSystems() {
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(registry);
        assertDoesNotThrow(() -> scanner.scan(
            "org.jetlinks.pro.property", "org.jetlinks.pro.smartpark.repair"));

        assertFalse(registry.containsBeanDefinition("propertyOrganizationService"));
        assertFalse(registry.containsBeanDefinition("propertySpaceController"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "org.jetlinks.pro.property.base.service.PropertyOrganizationService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "org.jetlinks.pro.patrol.service.PatrolTaskService"));
        assertEquals("org.jetlinks.pro.property.service.PropertyOrganizationService",
            registry.getBeanDefinition("repairPropertyOrganizationService").getBeanClassName());
        assertTrue(registry.containsBeanDefinition("repairPropertySpaceController"));
        assertTrue(registry.containsBeanDefinition("repairOrderController"));
        assertTrue(registry.containsBeanDefinition("commandCloudDirectory"));
    }

    @Test
    void anonymousRouteIsNarrowerThanAuthenticatedRepairRoutes() throws Exception {
        var resource = new ClassPathResource("route/property-repair.json");
        try (var stream = resource.getInputStream()) {
            var routes = new ObjectMapper().readTree(stream);
            assertEquals(4, routes.size());
            assertEquals("/repair/public/qr/**", routes.get(0).path("path").asText());
            assertFalse(routes.get(0).path("authorize").asBoolean());
            assertEquals("/repair/**", routes.get(1).path("path").asText());
            assertTrue(routes.get(1).path("authorize").asBoolean());
            assertEquals("/property-data/**", routes.get(2).path("path").asText());
            assertTrue(routes.get(2).path("authorize").asBoolean());
            assertEquals("/v3/api-docs/smart-park-repair-service", routes.get(3).path("path").asText());
            assertTrue(routes.get(3).path("authorize").asBoolean());
            assertTrue(routes.get(0).path("order").asInt() < routes.get(1).path("order").asInt());
        }
    }

    @Test
    void servicePreservesRepairAssetAndApiDiscoveryConfiguration() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("smart-park-repair-service", properties.getProperty("spring.application.name"));
        assertEquals("repairOrder", properties.getProperty(
            "jetlinks.assets.direct.tables.sp_repair_order.asset-type"));
        assertEquals("organizationId", properties.getProperty(
            "jetlinks.assets.direct.tables.sp_repair_order.dimension-column.org"));
        assertEquals("applicationId", properties.getProperty(
            "jetlinks.assets.direct.tables.sp_repair_order.dimension-column.business_application"));
        assertEquals("org.jetlinks.pro.smartpark.repair.web", properties.getProperty(
            "springdoc.group-configs[0].packages-to-scan[1]"));
    }
}
