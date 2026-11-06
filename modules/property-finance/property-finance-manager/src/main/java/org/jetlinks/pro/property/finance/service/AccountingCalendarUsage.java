package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 期间锁内检查改变归属的真实日期，不披露来源内容，也不因同年其他日期已有业务就禁止编辑。 */
@Component
@RequiredArgsConstructor
class AccountingCalendarUsage {
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<FundEntryEntity, String> funds;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<HandoverEntity, String> handovers;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    private record Range(LocalDate start, LocalDate end) {}

    Mono<Void> assertUnused(
            String project,
            List<AccountingPeriodEntity> before,
            List<AccountingCalendarRules.Period> after) {
        return Flux.fromIterable(transferredDates(before, after))
                .concatMap(range -> assertUnused(project, range))
                .then();
    }

    private Mono<Void> assertUnused(String project, Range range) {
        return Mono.zip(
                        count(payments, project, "paidAt", range, true),
                                count(refunds, project, "refundedAt", range, true),
                        count(funds, project, "occurredAt", range, true),
                                count(documents, project, "issuedAt", range, true),
                        count(handovers, project, "closingDate", range, false),
                                count(bills, project, "dueDate", range, false))
                .flatMap(
                        counts ->
                                counts.getT1() > 0
                                                || counts.getT2() > 0
                                                || counts.getT3() > 0
                                                || counts.getT4() > 0
                                                || counts.getT5() > 0
                                                || counts.getT6() > 0
                                        ? Mono.error(error("accounting_calendar_in_use", 409))
                                        : Mono.empty());
    }

    private <T> Mono<Integer> count(
            ReactiveRepository<T, String> repository,
            String project,
            String field,
            Range range,
            boolean timestamp) {
        String suffix = timestamp ? "T00:00" : "";
        // [较早边界,较晚边界)：末端午夜不改变归属，带小数秒的前一日仍在保护范围。
        return repository
                .createQuery()
                .where("projectId", project)
                .gte(field, range.start() + suffix)
                .lt(field, range.end() + suffix)
                .count();
    }

    private List<Range> transferredDates(
            List<AccountingPeriodEntity> before, List<AccountingCalendarRules.Period> after) {
        var ranges = new ArrayList<Range>();
        for (int index = 1; index < 12; index++) {
            var oldStart = LocalDate.parse(before.get(index).getStartDate());
            var newStart = after.get(index).start();
            if (!oldStart.equals(newStart))
                ranges.add(
                        oldStart.isBefore(newStart)
                                ? new Range(oldStart, newStart)
                                : new Range(newStart, oldStart));
        }
        ranges.sort(Comparator.comparing(Range::start));
        var merged = new ArrayList<Range>();
        for (var range : ranges) {
            if (merged.isEmpty() || range.start().isAfter(merged.get(merged.size() - 1).end())) {
                merged.add(range);
            } else {
                var last = merged.remove(merged.size() - 1);
                merged.add(
                        new Range(
                                last.start(),
                                last.end().isAfter(range.end()) ? last.end() : range.end()));
            }
        }
        return merged;
    }
}
