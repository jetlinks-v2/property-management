package org.jetlinks.pro.property.decoration.integration;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.decoration.model.DecorationChargeItemGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedDecorationChargeItemGatewayTest {
    @Test
    void disabledByDefaultAndRealProviderTakesPrecedence() {
        try (var context = context(null, false)) {
            assertNull(context.getBeanProvider(DecorationChargeItemGateway.class).getIfAvailable());
        }
        try (var context = context("false", false)) {
            assertNull(context.getBeanProvider(DecorationChargeItemGateway.class).getIfAvailable());
        }
        try (var context = context("true", false)) {
            assertInstanceOf(SimulatedDecorationChargeItemGateway.class,
                context.getBeanProvider(DecorationChargeItemGateway.class).getIfAvailable());
        }
        try (var context = context("true", true)) {
            assertSame(context.getBean("realChargeItems"),
                context.getBeanProvider(DecorationChargeItemGateway.class).getIfAvailable());
        }
    }

    @Test
    void selectorCanSearchAndPageWithoutChangingCallerQuery() {
        var gateway = new SimulatedDecorationChargeItemGateway();
        var query = new QueryParamEntity();
        query.setPageSize(1);
        query.setPageIndex(1);
        StepVerifier.create(gateway.query("project", query)).assertNext(page -> {
            assertEquals(2, page.getTotal());
            assertEquals(1, page.getPageIndex());
            assertEquals(List.of(new DecorationChargeItemGateway.ChargeItem(
                "SIM-DECORATION-SERVICE", "装修服务费")), page.getData());
        }).verifyComplete();
        var search = QueryParamEntity.newQuery().where("name", "like", "保证金").getParam();
        StepVerifier.create(gateway.query("project", search)).assertNext(page -> {
            assertEquals(1, page.getTotal());
            assertEquals("SIM-DECORATION-DEPOSIT", page.getData().get(0).id());
        }).verifyComplete();
        var empty = QueryParamEntity.newQuery().where("name", "like", "不存在").getParam();
        StepVerifier.create(gateway.query("project", empty)).assertNext(page -> {
            assertEquals(0, page.getTotal());
            assertTrue(page.getData().isEmpty());
        }).verifyComplete();
        assertEquals(1, query.getPageIndex());
        assertEquals(1, query.getPageSize());
    }

    @Test
    void unsupportedConditionsFailInsteadOfReturningMisleadingData() {
        var query = QueryParamEntity.newQuery().where("id", "real-item").getParam();
        StepVerifier.create(new SimulatedDecorationChargeItemGateway().query("project", query))
            .expectErrorSatisfies(error -> assertEquals(400, ((BusinessException) error).getStatus()))
            .verify();
    }

    private AnnotationConfigApplicationContext context(String enabled, boolean realProvider) {
        var context = new AnnotationConfigApplicationContext();
        if (enabled != null) context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("simulation", Map.of("decoration.integrations.simulated.enabled", enabled)));
        context.register(SimulatedDecorationChargeItemGateway.class);
        if (realProvider) context.registerBean("realChargeItems", DecorationChargeItemGateway.class,
            () -> (projectId, query) -> Mono.just(PagerResult.of(0, List.of(), query)));
        context.refresh();
        return context;
    }
}
