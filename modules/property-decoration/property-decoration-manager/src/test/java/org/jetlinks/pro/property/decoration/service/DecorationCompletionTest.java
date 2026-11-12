package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 单独验证结束规则；事务、范围、并发和回执持久化由已有数据库测试覆盖。 */
class DecorationCompletionTest {
    private final DecorationApplicationService applications = mock(DecorationApplicationService.class);
    private final DecorationReviewService reviews = new DecorationReviewService(applications, mock(DecorationAttachmentService.class));
    private final DecorationApplicationEntity row = new DecorationApplicationEntity();

    DecorationCompletionTest() {
        row.setDetails(new DecorationModels.ApplicationDetails()); row.setRefundStatus(0);
    }

    private void mutation(String action, DecorationRequests.Action input) {
        when(applications.mutate(eq("application"), eq(action), same(input), eq(false), any()))
            .thenAnswer(call -> Mono.defer(() -> {
                BiFunction<DecorationApplicationEntity, DecorationScope.Context, Mono<Void>> operation = call.getArgument(4);
                return operation.apply(row, null).thenReturn(row);
            }));
    }

    @Test void pendingOrUnstartedRefundCannotEndOrReleaseHouse() {
        row.setStatus("acceptance_passed"); row.setActiveHouseKey("occupied");
        var end = new DecorationRequests.Action(); end.setRemark("提前结束"); mutation("end", end);
        var time = new DecorationRequests.ChangeActualEndTime(); time.setActualEndTime(2000L); mutation("changeActualEndTime", time);
        for (int refund : new int[]{0, 1}) {
            row.setRefundStatus(refund);
            StepVerifier.create(reviews.end("application", end)).expectErrorMatches(this::refundRequired).verify();
            StepVerifier.create(reviews.changeActualEndTime("application", time)).expectErrorMatches(this::refundRequired).verify();
            assertEquals("acceptance_passed", row.getStatus()); assertNull(row.getActualEndTime());
            assertEquals("occupied", row.getActiveHouseKey()); assertEquals(refund, row.getRefundStatus());
        }
    }

    @Test void correctingCompletedTimeAndRemarkDoesNotRewriteFinancialFacts() {
        row.setStatus("finished"); row.setRefundStatus(2); row.setActualEndTime(2000L);
        row.getDetails().setStartTime(1000L); row.setRefundTime(2000L); row.setRefundReference("receipt");
        row.setRefundedAmount(new BigDecimal("1000"));
        var time = new DecorationRequests.ChangeActualEndTime(); time.setActualEndTime(3000L); mutation("changeActualEndTime", time);
        StepVerifier.create(reviews.changeActualEndTime("application", time)).expectNext(row).verifyComplete();
        var end = new DecorationRequests.Action(); end.setRemark("补充说明"); mutation("end", end);
        StepVerifier.create(reviews.end("application", end)).expectNext(row).verifyComplete();
        assertEquals(3000L, row.getActualEndTime()); assertEquals("补充说明", row.getEndReason());
        assertEquals("finished", row.getStatus()); assertNull(row.getActiveHouseKey());
        assertEquals(2, row.getRefundStatus()); assertEquals(2000L, row.getRefundTime());
        assertEquals("receipt", row.getRefundReference()); assertEquals(new BigDecimal("1000"), row.getRefundedAmount());
    }

    @Test void completedTimeStillCannotPrecedeApplicationStart() {
        row.setRefundStatus(2); row.getDetails().setStartTime(1000L); row.setActualEndTime(2000L);
        var time = new DecorationRequests.ChangeActualEndTime(); time.setActualEndTime(500L); mutation("changeActualEndTime", time);
        StepVerifier.create(reviews.changeActualEndTime("application", time))
            .expectErrorMatches(error -> error instanceof BusinessException && error.getMessage().equals("error.decoration.invalid_dates")).verify();
        assertEquals(2000L, row.getActualEndTime());
    }

    @Test void refundCompletionLocksFurtherBusinessEvenWithoutEndTime() {
        row.setRefundStatus(2);
        assertEquals("error.decoration.ended", assertThrows(BusinessException.class, () -> DecorationRules.active(row)).getMessage());
    }

    private boolean refundRequired(Throwable error) {
        return error instanceof BusinessException && error.getMessage().equals("error.decoration.refund_completion_required");
    }
}
