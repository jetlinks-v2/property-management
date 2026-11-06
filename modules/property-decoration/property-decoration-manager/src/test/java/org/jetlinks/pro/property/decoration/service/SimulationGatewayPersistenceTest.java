package org.jetlinks.pro.property.decoration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.jetlinks.pro.property.decoration.entity.DecorationSimulationReceiptEntity;
import org.jetlinks.pro.property.decoration.integration.*;
import org.jetlinks.pro.property.decoration.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证模拟提供方的可持久幂等回执，不模拟其仓储或事务。 */
@SpringBootTest(classes=SimulationGatewayPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties="decoration.integrations.simulated.enabled=true")
class SimulationGatewayPersistenceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){DecorationPersistenceTest.database(r);}
    @Autowired SimulatedDecorationGateway gateway;
    @Autowired ReactiveRepository<DecorationSimulationReceiptEntity,String> receipts;
    @BeforeEach void clear(){receipts.deleteById(receipts.createQuery().fetch().map(DecorationSimulationReceiptEntity::getId)).block();}
    @Test void paymentsRefundsAndNotificationsReturnStableDistinctMarkedReceipts(){
        var request=request("key",BigDecimal.TEN);
        var first=gateway.payment(request).block();
        assertEquals("SIMULATED_DECORATION",first.provider());assertEquals(DecorationFinanceGateway.Status.SUCCEEDED,first.status());
        assertEquals(first,gateway.payment(request).block());
        var refund=gateway.refund(request).block();assertEquals(DecorationFinanceGateway.Direction.REFUND,refund.direction());
        assertNotEquals(first.reference(),refund.reference());assertEquals(refund,gateway.refund(request).block());
        var notice=new DecorationNotificationGateway.Notice("notice","decoration","task","app","tenant","project","owner","rectification");
        var received=gateway.send(notice).block();assertTrue(received.accepted());assertEquals("SIMULATED_DECORATION",received.provider());
        assertEquals(received,gateway.send(notice).block());assertEquals(3,receipts.createQuery().count().block().intValue());
    }
    @Test void changedRequestCannotReuseIdempotencyKeyAndConcurrentRequestsHaveOneReceipt(){
        var request=request("same",BigDecimal.TEN);
        var results=reactor.core.publisher.Flux.range(0,5).flatMap(i->gateway.payment(request),5).collectList().block();
        assertEquals(1,results.stream().distinct().count());assertEquals(1,receipts.createQuery().count().block().intValue());
        StepVerifier.create(gateway.payment(request("same",BigDecimal.ONE)))
            .expectErrorMatches(e->e.getMessage().contains("request_conflict")).verify();
    }
    @Test void receiptSurvivesProviderRecreationAndKeepsOfflineReference(){
        var request=new DecorationFinanceGateway.Request("offline","d","a","t","p","h","owner","charge",BigDecimal.TEN,"TEST-ONLY-REF",true);
        var paid=gateway.payment(request).block();assertEquals("TEST-ONLY-REF",paid.reference());
        var another=new SimulatedDecorationGateway(receipts,new SimulationReceiptWriter(receipts),new ObjectMapper());
        assertEquals(paid,another.payment(request).block());
    }
    private DecorationFinanceGateway.Request request(String key,BigDecimal amount){return new DecorationFinanceGateway.Request(key,"d","a","t","p","h","owner","charge",amount,null,false);}
    @SpringBootConfiguration @EnableTransactionManagement(proxyTargetClass=true)
    @EnableEasyormRepository("org.jetlinks.pro.property.decoration.entity")
    @Import({SimulatedDecorationGateway.class,SimulationReceiptWriter.class})
    @ImportAutoConfiguration({EasyormConfiguration.class,R2dbcSqlExecutorConfiguration.class,R2dbcAutoConfiguration.class,R2dbcTransactionManagerAutoConfiguration.class,TransactionAutoConfiguration.class,EntityFactoryConfiguration.class})
    static class Config {@Bean ObjectMapper json(){return new ObjectMapper();}}
}
