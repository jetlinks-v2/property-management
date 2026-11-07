package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepairCandidateAvailabilityTest {
    @Test void boundedChecksDoNotWaitForAnUnrelatedSlowEmployeeAndCancelAfterMatch() {
        var people=mock(RepairDirectory.class);
        var assignees=mock(RepairAssigneeService.class);
        var order=new RepairOrderEntity();
        var context=new RepairContext("tenant","app","reporter","报事人");
        var subscribed=new AtomicInteger();
        var cancelled=new AtomicInteger();
        List<Sinks.One<Boolean>> checks=new ArrayList<>();
        when(people.employeesFor(order)).thenReturn(Flux.range(0,20).map(i->{
            var person=new PropertyUserInfo();person.setId("person"+i);return person;
        }));
        when(assignees.canHandle(any(),eq(order),eq(context),eq("workers")))
            .thenAnswer(invocation->{
                var check=Sinks.<Boolean>one();checks.add(check);
                return check.asMono().doOnSubscribe(s->subscribed.incrementAndGet())
                    .doOnCancel(cancelled::incrementAndGet);
            });
        var service=new RepairCandidateService(people,assignees,mock(RepairOrderService.class));
        StepVerifier.create(service.available("workers",order,context))
            .then(()->{
                assertEquals(8,subscribed.get());
                checks.get(1).tryEmitValue(true);
            }).expectNext(true).verifyComplete();
        assertEquals(8,subscribed.get());assertTrue(cancelled.get()>=7);
    }

    @Test void submissionWithoutAnEligibleEmployeeRemainsBlocked() {
        var people=mock(RepairDirectory.class);var assignees=mock(RepairAssigneeService.class);
        var orders=mock(RepairOrderService.class);var order=new RepairOrderEntity();order.setStatus(0);
        var category=new org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity();
        category.setConfiguration(java.util.Map.of("flowEnabled",true,"followRoleId","workers"));
        when(people.employeesFor(order)).thenReturn(Flux.empty());
        var service=new RepairCandidateService(people,assignees,orders);
        StepVerifier.create(service.initialize(order,category,new RepairContext("tenant","app","reporter","报事人")))
            .expectErrorMatches(error->error instanceof org.hswebframework.web.exception.BusinessException
                && "error.repair.role_has_no_eligible_users".equals(error.getMessage())).verify();
        assertEquals(0,order.getStatus());assertNull(order.getCandidateRoleId());verifyNoInteractions(orders);
    }

    @Test void noCandidateRemainsFalseAndInfrastructureFailurePropagates() {
        var people=mock(RepairDirectory.class);var assignees=mock(RepairAssigneeService.class);
        var order=new RepairOrderEntity();var context=new RepairContext("tenant","app","reporter","报事人");
        var person=new PropertyUserInfo();
        when(people.employeesFor(order)).thenReturn(Flux.just(person));
        when(assignees.canHandle(person,order,context,"workers")).thenReturn(reactor.core.publisher.Mono.just(false));
        var service=new RepairCandidateService(people,assignees,mock(RepairOrderService.class));
        StepVerifier.create(service.available("workers",order,context)).expectNext(false).verifyComplete();
        when(assignees.canHandle(person,order,context,"workers"))
            .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("directory unavailable")));
        StepVerifier.create(service.available("workers",order,context)).expectErrorMessage("directory unavailable").verify();
    }
}
