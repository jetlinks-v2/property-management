package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 内部余额核销复用应收计算与资金分配账本，绝不创建一次虚构的现金收入。 */
@Component
@RequiredArgsConstructor
public class FundOffsetService {
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final PaymentRules rules;
    private final PaymentLedger ledger;

    Mono<Prepared> prepare(
            String project, FundAccountEntity a, FundsInput i, LocalDate date, boolean lock) {
        if (!"OFFSET".equals(i.getAction())) return Mono.empty();
        var cash = new CashierInput();
        cash.setCompanyId(a.getCompanyId());
        cash.setPayerName(a.getCustomerName());
        cash.setPayerRelationshipId(a.getRelationshipId());
        cash.setPaidAt(i.getOccurredAt());
        cash.setRemark(i.getReason());
        cash.setAttachmentName(i.getAttachmentName());
        cash.setAttachmentFileId(i.getAttachmentFileId());
        cash.setItems(i.getItems());
        cash.setTenders(List.of(new CashierInput.Tender(a.getType(), a.getId(), i.getAmount())));
        return Flux.fromIterable(
                        i.getItems().stream()
                                .sorted(Comparator.comparing(CashierInput.Item::id))
                                .toList())
                .concatMap(
                        item -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return bills.createQuery()
                                    .setParam(query)
                                    .where("id", item.id())
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                                    .flatMap(
                                            row ->
                                                    AssetsHolder.assertPermission(
                                                                    AssetType.of("space"),
                                                                    row.getSpaceId(),
                                                                    CrudAssetPermission.save)
                                                            .thenReturn(row))
                                    .doOnNext(
                                            row -> {
                                                if (!Objects.equals(
                                                                row.getSpaceId(), a.getSpaceId())
                                                        || !Objects.equals(
                                                                row.getSubjectId(),
                                                                a.getSubjectId())
                                                        || !SettingRules.selectedCustomer(
                                                                row, a.getCustomerId()))
                                                    throw error("fund_offset_invalid", 409);
                                                var company =
                                                        SettingRules.standard(row.getSnapshotJson())
                                                                .getCompanyId();
                                                if (company != null
                                                        && !a.getCompanyId().equals(company))
                                                    throw error("fund_offset_invalid", 409);
                                            });
                        })
                .collectList()
                .map(rows -> new Prepared(rows, rules.prepareBalance(rows, cash, date)));
    }

    Mono<Void> persist(
            String id,
            String fingerprint,
            FundAccountEntity a,
            FundsInput i,
            Prepared p,
            FinanceCompanyEntity company,
            Authentication auth) {
        if (p == null) return Mono.empty();
        var pay = new PaymentEntity();
        pay.setId(id);
        init(pay, auth);
        pay.setProjectId(a.getProjectId());
        pay.setCompanyId(company.getId());
        pay.setCompanyName(company.getCompanyName());
        pay.setRequestFingerprint(fingerprint);
        pay.setStatus("SUCCEEDED");
        pay.setPayerName(a.getCustomerName());
        pay.setPayerRelationshipId(a.getRelationshipId());
        pay.setPaidAt(i.getOccurredAt());
        pay.setAmount(i.getAmount());
        pay.setRemark(i.getReason());
        pay.setAttachmentName(i.getAttachmentName());
        pay.setAttachmentFileId(i.getAttachmentFileId());
        var funding =
                new CashierConfiguration.Funding(
                        a.getType(),
                        "ADVANCE".equals(a.getType()) ? "预收款结转" : "押金结转",
                        a.getId(),
                        null,
                        null,
                        null,
                        a.getVersion());
        return ledger.persist(
                        pay,
                        p.rows(),
                        p.result(),
                        new CashierConfiguration.Resolved(company, List.of(funding)),
                        auth)
                .then();
    }

    record Prepared(List<ReceivableEntity> rows, PaymentRules.Result result) {}
}
