package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.jetlinks.pro.property.finance.payment.TemporaryInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 当前客户必须来自本房屋的有效关系；历史收款详情及退款只读取收款快照。 */
@Component
@RequiredArgsConstructor
public class TemporarySourceService {
    private final HouseArchiveSource archive;
    private final CollectionStandardSource standards;

    Mono<PropertyHouseContext> context(String project, TemporaryInput input, boolean lock) {
        return AssetsHolder.assertPermission(
                        AssetType.of("space"), input.getSpaceId(), CrudAssetPermission.save)
                .then(archive.context(project, input.getSpaceId(), lock))
                .map(
                        context -> {
                            if (!project.equals(context.house().projectId())
                                    || !input.getSpaceId().equals(context.house().spaceId()))
                                throw error("temporary_invalid", 409);
                            customer(input, context);
                            return context;
                        });
    }

    Mono<Prepared> prepare(
            String project, TemporaryInput input, PropertyHouseContext context, boolean lock) {
        var referenceLock =
                lock
                        ? standards.lock(
                                project, input.getCompanyId(), Set.of(input.getStandardId()))
                        : Mono.<Void>empty();
        return referenceLock
                .then(standards.current(project, input.getStandardId(), input.getCompanyId()))
                .map(
                        standard -> {
                            if (input.getSubjectId() != null
                                    && !input.getSubjectId().equals(standard.getSubjectId()))
                                throw error("setting_subject_unavailable", 409);
                            // 起止日期可覆盖未来期间；标准必须覆盖本次业务期间，不能拿已失效标准收费。
                            if (standard.getExecuteDate() != null
                                            && standard.getExecuteDate()
                                                            .compareTo(input.getStartDate())
                                                    > 0
                                    || standard.getCancelDate() != null
                                            && standard.getCancelDate()
                                                            .compareTo(input.getEndDate())
                                                    < 0)
                                throw error("setting_standard_unavailable", 409);
                            var customer = customer(input, context);
                            var snapshot = new LinkedHashMap<String, Object>();
                            snapshot.put("house", context.house());
                            snapshot.put("customer", customer);
                            snapshot.put("standard", standard);
                            snapshot.put("tax", JSON.parseObject(standard.getTaxSnapshotJson()));
                            snapshot.put(
                                    "quantity",
                                    input.getQuantity() == null
                                            ? null
                                            : input.getQuantity().toPlainString());
                            snapshot.put("startDate", input.getStartDate());
                            snapshot.put("endDate", input.getEndDate());
                            snapshot.put("accountPeriod", input.getAccountPeriod());
                            return new Prepared(
                                    context.house(),
                                    customer,
                                    standard,
                                    JSON.toJSONString(snapshot));
                        });
    }

    private PropertyHouseCustomer customer(TemporaryInput input, PropertyHouseContext context) {
        return context.customers().stream()
                .filter(c -> c.relationshipId().equals(input.getRelationshipId()))
                .findFirst()
                .orElseThrow(() -> error("cashier_payer_invalid", 409));
    }

    record Prepared(
            PropertyHouseInfo house,
            PropertyHouseCustomer customer,
            StandardEntity standard,
            String snapshot) {}
}
