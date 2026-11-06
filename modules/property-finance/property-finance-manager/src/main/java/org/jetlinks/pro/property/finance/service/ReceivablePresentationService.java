package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.DocumentEntity;
import org.jetlinks.pro.property.finance.entity.DocumentLineEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.receivable.ReceivableView;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 只对已完成项目及空间授权的账单补展示事实；批量读取票据，避免列表逐行查询。 */
@Component
@RequiredArgsConstructor
public class ReceivablePresentationService {
    private final ReactiveRepository<DocumentLineEntity, String> lines;
    private final ReactiveRepository<DocumentEntity, String> documents;

    public Mono<List<ReceivableView>> views(
            List<ReceivableEntity> rows, String projectName, boolean detail) {
        if (rows.isEmpty()) return Mono.just(List.of());
        String project = rows.get(0).getProjectId();
        var date = LocalDate.now();
        var views =
                rows.stream()
                        .map(
                                row -> {
                                    var view = ReceivableView.of(row, detail, date);
                                    view.setProjectName(projectName);
                                    return view;
                                })
                        .toList();
        return lines.createQuery()
                .where("projectId", project)
                .and("sourceType", "RECEIVABLE")
                .and("deleted", false)
                .in("sourceId", rows.stream().map(ReceivableEntity::getId).toList())
                .fetch()
                .collectList()
                .flatMap(
                        found -> {
                            if (found.isEmpty()) return Mono.just(views);
                            var documentIds =
                                    found.stream()
                                            .map(DocumentLineEntity::getDocumentId)
                                            .distinct()
                                            .toList();
                            return documents
                                    .createQuery()
                                    .where("projectId", project)
                                    .and("documentType", "INVOICE")
                                    .and("deleted", false)
                                    .in("id", documentIds)
                                    .fetch()
                                    .map(DocumentEntity::getId)
                                    .collectList()
                                    .map(
                                            ids -> {
                                                var valid = new HashSet<>(ids);
                                                var amounts = new HashMap<String, BigDecimal[]>();
                                                // 红票使用负金额，按蓝红事实净额汇总；收据不产生销项税。
                                                for (var line : found) {
                                                    if (!valid.contains(line.getDocumentId()))
                                                        continue;
                                                    var total =
                                                            amounts.computeIfAbsent(
                                                                    line.getSourceId(),
                                                                    ignored ->
                                                                            new BigDecimal[] {
                                                                                BigDecimal.ZERO,
                                                                                BigDecimal.ZERO
                                                                            });
                                                    total[0] = total[0].add(line.getAmount());
                                                    total[1] = total[1].add(line.getTaxAmount());
                                                }
                                                for (var view : views) {
                                                    var total = amounts.get(view.getId());
                                                    if (total == null || total[0].signum() <= 0)
                                                        continue;
                                                    view.setOutputTaxAmount(total[1]);
                                                    view.setIncomeExcludingTax(
                                                            view.getActualReceivableAmount()
                                                                    .subtract(total[1]));
                                                }
                                                return views;
                                            });
                        });
    }
}
