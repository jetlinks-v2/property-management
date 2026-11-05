package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.FinancialPeriodControlEntity;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;

/** 仅测试装配：在真实事务内模拟已结日期，验证各业务入口而非伪造期间锁。 */
@RequiredArgsConstructor
class FinancialPeriodTestSupport {
    private final FinancialPeriodGate gate;
    private final ReactiveRepository<FinancialPeriodControlEntity, String> controls;

    @Transactional
    public Mono<Void> close(String project, LocalDate date) {
        return gate.lock(project)
                .flatMap(
                        control ->
                                controls.createUpdate()
                                        .set("closedThrough", date.toString())
                                        .where("id", control.getId())
                                        .execute())
                .then();
    }

    static void rejectsClosed(Mono<?> operation) {
        StepVerifier.create(operation)
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        && error.getMessage().contains("period_closed"))
                .verify(CashierPersistenceTest.WAIT);
    }
}
