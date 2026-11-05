package org.jetlinks.pro.property.decoration.configuration;

import org.jetlinks.pro.property.decoration.service.DecorationInspectionGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MBean导出和订阅结果；失败不吞掉，不把最后成功时间更新为失败时间。 */
class DecorationInspectionSchedulerTest {
    @Test void metricsAreRegisteredUpdatedAndRemovedWithContext() throws Exception {
        MBeanServer server;
        var name = new ObjectName("org.jetlinks:type=DecorationInspection,name=Generator");
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            server = context.getBean(MBeanServer.class);
            assertTrue(server.isRegistered(name));
            var generator = context.getBean(DecorationInspectionGenerator.class);
            var scheduler = context.getBean(DecorationInspectionScheduler.class);
            when(generator.generateBatch(anyLong())).thenReturn(Mono.just(3));
            StepVerifier.create(scheduler.tick()).verifyComplete();
            assertEquals(3L, server.getAttribute(name, "GeneratedCount"));
            long successful = (long) server.getAttribute(name, "LastSuccessTime");
            assertTrue(successful > 0);
            when(generator.generateBatch(anyLong())).thenReturn(Mono.error(new IllegalStateException("unavailable")));
            StepVerifier.create(scheduler.tick()).expectErrorMessage("unavailable").verify();
            assertEquals(1L, server.getAttribute(name, "FailureCount"));
            assertEquals(successful, server.getAttribute(name, "LastSuccessTime"));
        }
        assertFalse(server.isRegistered(name));
    }
    @Configuration @EnableMBeanExport(server="inspectionMBeanServer")
    static class Config {
        @Bean MBeanServer inspectionMBeanServer() { return MBeanServerFactory.newMBeanServer(); }
        @Bean DecorationInspectionGenerator generator() { return mock(DecorationInspectionGenerator.class); }
        @Bean DecorationInspectionScheduler scheduler(DecorationInspectionGenerator generator) { return new DecorationInspectionScheduler(generator); }
    }
}
