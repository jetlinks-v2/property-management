package org.jetlinks.pro.property.quality.service;

import org.junit.jupiter.api.Test;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QualityCandidateScanTest {
    private PropertyUserInfo person(int id) {
        var person = new PropertyUserInfo();
        person.setId(String.valueOf(id));
        return person;
    }

    @Test
    void overlapsRemoteChecksWithinBoundAndRetainsOnlyAuthorizedPeopleInOrder() {
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> QualityCandidateScan.filter(Flux.range(0, 320).map(this::person), person ->
            Mono.defer(() -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                return Mono.delay(Duration.ofMillis(100))
                    .map(ignored -> Integer.parseInt(person.getId()) % 2 == 0)
                    .doOnSuccess(ignored -> active.decrementAndGet());
            })).map(PropertyUserInfo::getId).collectList())
            .thenAwait(Duration.ofSeconds(2))
            .assertNext(ids -> {
                assertEquals(160, ids.size());
                for (int i = 0; i < ids.size(); i++) assertEquals(String.valueOf(i * 2), ids.get(i));
                assertTrue(maximum.get() > 1);
                assertTrue(maximum.get() <= 32);
                assertEquals(0, active.get());
            }).verifyComplete();
    }

    @Test
    void doesNotHideAuthorizationInfrastructureFailure() {
        var failure = new IllegalStateException("authorization unavailable");
        StepVerifier.create(QualityCandidateScan.filter(Flux.just(person(1)), ignored -> Mono.error(failure)))
            .expectErrorMatches(error -> error == failure).verify();
    }

    @Test
    void cancellationCancelsPendingRemoteAuthorizationChecks() {
        var active = new AtomicInteger();
        StepVerifier.create(QualityCandidateScan.filter(Flux.range(0, 320).map(this::person), person ->
            Mono.<Boolean>never().doOnSubscribe(ignored -> active.incrementAndGet())
                .doOnCancel(active::decrementAndGet)))
            .then(() -> assertEquals(32, active.get())).thenCancel().verify();
        assertEquals(0, active.get());
    }
}
