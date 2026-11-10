package org.jetlinks.pro.property.facility;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/** 防止设施接口被基础服务的 /property/** 兜底路由抢先转发。 */
class FacilityModuleAssemblyTest {
    @Test
    void facilityRoutesRequireAuthenticationAndPrecedeBaseCatchAll() throws Exception {
        try (var stream = new ClassPathResource("route/property-facility-energy.json").getInputStream()) {
            var routes = new ObjectMapper().readTree(stream);
            assertEquals(2, routes.size());
            var business = routes.get(0);
            assertEquals("/property/facility-energy/**", business.path("path").asText());
            assertEquals("/v3/api-docs/property-facility-energy-service", routes.get(1).path("path").asText());
            for (var route : routes) {
                assertTrue(route.path("authorize").asBoolean());
                assertTrue(route.path("order").asInt() < -10);
            }
            var pattern = new PathPatternParser().parse(business.path("path").asText());
            assertTrue(pattern.matches(PathContainer.parsePath("/property/facility-energy/context/projects")));
            assertTrue(pattern.matches(PathContainer.parsePath("/property/facility-energy/devices/sync/_query")));
            assertFalse(pattern.matches(PathContainer.parsePath("/property/organization/_tree")));
            assertFalse(pattern.matches(PathContainer.parsePath("/property/finance/projects")));
        }
    }
}
