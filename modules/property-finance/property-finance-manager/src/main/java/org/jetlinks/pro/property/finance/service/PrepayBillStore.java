package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 调用方已持有基础、公司、科目、设置和活动锁；统一按ID批量锁原账，新增保留稳定billKey。 */
@Component
@RequiredArgsConstructor
public class PrepayBillStore {
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final PrepayPeriodPricing pricing;
    private final ReceivableCalculationService calculation;

    Mono<List<ReceivableEntity>> prepare(PrepayQuoteSnapshot snapshot, Authentication auth) {
        var setting = JSON.parseObject(snapshot.settingJson(), HouseSettingEntity.class);
        var ids =
                snapshot.periods().stream()
                        .map(PrepayQuoteSnapshot.Period::receivableId)
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.setPaging(false);
        query.toQuery().orderByAsc("id");
        Mono<List<ReceivableEntity>> existing =
                ids.isEmpty()
                        ? Mono.just(List.of())
                        : bills.createQuery()
                                .setParam(query)
                                .where("projectId", setting.getProjectId())
                                .in("id", ids)
                                .fetch()
                                .collectList();
        return existing.flatMapMany(
                        rows -> {
                            var indexed = new HashMap<String, ReceivableEntity>();
                            rows.forEach(row -> indexed.put(row.getId(), row));
                            return Flux.fromIterable(snapshot.periods())
                                    .concatMap(
                                            period -> {
                                                if (period.receivableId() != null) {
                                                    var row = indexed.get(period.receivableId());
                                                    if (row == null
                                                            || !Objects.equals(
                                                                    row.getVersion(),
                                                                    period.expectedVersion())
                                                            || !PaymentRules.digest(
                                                                            JSON.toJSONString(row))
                                                                    .equals(
                                                                            period
                                                                                    .rowFingerprint()))
                                                        return Mono.error(
                                                                error(
                                                                        "prepay_source_changed",
                                                                        409));
                                                    return Mono.just(row);
                                                }
                                                var row =
                                                        pricing.price(
                                                                        setting,
                                                                        new PrepayQuoteRules.Window(
                                                                                LocalDate.parse(
                                                                                        period
                                                                                                .start()),
                                                                                LocalDate.parse(
                                                                                        period
                                                                                                .end())))
                                                                .draft();
                                                if (!row.getBillKey().equals(period.billKey()))
                                                    return Mono.error(
                                                            error("prepay_source_changed", 409));
                                                row.setId(UUID.randomUUID().toString());
                                                init(row, auth);
                                                calculation.calculate(
                                                        row,
                                                        org.jetlinks.pro.property.finance.setting
                                                                .SettingRules.standard(
                                                                row.getSnapshotJson()),
                                                        LocalDate.parse(snapshot.businessDate()),
                                                        org.jetlinks.pro.property.finance.setting
                                                                .BillingTaskKind.ALL);
                                                return bills.insert(row).thenReturn(row);
                                            });
                        })
                .collectList();
    }
}
