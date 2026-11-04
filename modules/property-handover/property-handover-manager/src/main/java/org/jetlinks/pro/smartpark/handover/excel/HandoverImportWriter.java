package org.jetlinks.pro.smartpark.handover.excel;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.smartpark.handover.api.ProblemRequest;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** 独立代理拥有全批事务；不能在解析循环中边验证边保存，任何提交失败均整体回滚。 */
@Service
@RequiredArgsConstructor
public class HandoverImportWriter {
    private final HandoverConfigurationService configuration;
    private final HandoverProblemService problems;
    private final HandoverCategoryRepository categories;
    private final HandoverLedgerRepository ledgers;
    private final HandoverEquipmentRepository equipment;
    private final HandoverRecordRepository records;
    private final HandoverItemRepository items;

    public Mono<Void> validate(Object row) {
        return row instanceof ProblemRequest problem
                ? problems.validate(problem).then()
                : configuration.validate((HandoverEntity) row).then();
    }

    @Transactional
    public Mono<Long> write(List<HandoverImportMapper.Prepared> rows) {
        return Flux.fromIterable(rows).concatMap(row -> save(row.value())).count();
    }

    private Mono<?> save(Object value) {
        if (value instanceof HandoverCategoryEntity row) return configuration.save(categories, row);
        if (value instanceof HandoverLedgerEntity row) return configuration.save(ledgers, row);
        if (value instanceof HandoverEquipmentEntity row) return configuration.save(equipment, row);
        if (value instanceof HandoverRecordEntity row) return configuration.save(records, row);
        if (value instanceof HandoverItemEntity row) return configuration.save(items, row);
        return problems.create((ProblemRequest) value);
    }
}
