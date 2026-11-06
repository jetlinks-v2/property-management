package org.jetlinks.pro.property.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.ClassPathResource;

/** 验证独立收费服务的扫描边界及网关路由，不替代实际 SaaS 联调。 */
class FinanceModuleAssemblyTest {
    @Test
    void financeScansWithoutBaseOrSiblingManagers() {
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(registry);
        assertDoesNotThrow(() -> scanner.scan("org.jetlinks.pro.property"));
        assertTrue(registry.containsBeanDefinition("historicalReceivableService"));
        assertTrue(registry.containsBeanDefinition("houseSettingService"));
        for (var implementation : new String[] {
            "org.jetlinks.pro.property.base.service.PropertyOrganizationService",
            "org.jetlinks.pro.smartpark.repair.service.RepairOrderService",
            "org.jetlinks.pro.patrol.service.PatrolTaskService",
            "org.jetlinks.pro.smartpark.handover.service.HandoverProblemService"
        }) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(implementation));
        }
    }

    @Test
    void financeRoutesRequireAuthenticationAndPrecedeBaseCatchAll() throws Exception {
        try (var stream = new ClassPathResource("route/property-finance.json").getInputStream()) {
            var routes = new ObjectMapper().readTree(stream);
            assertEquals(2, routes.size());
            assertEquals("/property/finance/**", routes.get(0).path("path").asText());
            assertEquals("/v3/api-docs/property-finance-service", routes.get(1).path("path").asText());
            for (var route : routes) {
                assertTrue(route.path("authorize").asBoolean());
                assertTrue(route.path("order").asInt() < -10);
            }
        }
    }
}
