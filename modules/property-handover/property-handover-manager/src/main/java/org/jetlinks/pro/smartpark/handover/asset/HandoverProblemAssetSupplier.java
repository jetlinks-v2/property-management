package org.jetlinks.pro.smartpark.handover.asset;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.smartpark.handover.service.HandoverProblemRepository;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;

/** 提供真实业务资产，供平台查看归属；不以创建人组织替代项目或责任单位。 */
@Component
@RequiredArgsConstructor
public class HandoverProblemAssetSupplier implements AssetSupplier {
    private final HandoverProblemRepository problems;

    public List<? extends AssetType> getTypes() {
        return List.of(HandoverAssetType.values());
    }

    public Flux<? extends Asset> getAssets(AssetType type, Collection<?> ids) {
        if (!type.isSameType(HandoverAssetType.handoverProblem.getId())) return Flux.empty();
        return problems.createQuery()
                .in("id", ids)
                .and("deleted", false)
                .fetch()
                .map(
                        p ->
                                new DefaultAsset(
                                                p.getId(),
                                                p.getTitle() == null
                                                        ? p.getDescription()
                                                        : p.getTitle(),
                                                HandoverAssetType.handoverProblem)
                                        .withEntity(p));
    }
}
