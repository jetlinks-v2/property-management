package org.jetlinks.pro.patrol.asset;

import lombok.AllArgsConstructor;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.Asset;
import org.jetlinks.pro.assets.AssetSupplier;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.DefaultAsset;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.service.PatrolCategoryService;
import org.jetlinks.pro.patrol.service.PatrolPlanService;
import org.jetlinks.pro.patrol.service.PatrolPointService;
import org.jetlinks.pro.patrol.service.PatrolPostService;
import org.jetlinks.pro.patrol.service.PatrolRosterService;
import org.jetlinks.pro.patrol.service.PatrolStandardService;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;

/** withEntity 保留平台维护的 tenant 元数据，不能只用裸 ID 构造资产。 */
@Component
@AllArgsConstructor
public class PatrolAssetSupplier implements AssetSupplier {
    private final PatrolCategoryService categories;
    private final PatrolPointService points;
    private final PatrolStandardService standards;
    private final PatrolPostService posts;
    private final PatrolRosterService rosters;
    private final PatrolPlanService plans;

    public List<? extends AssetType> getTypes() {
        return List.of(PatrolAssetType.values());
    }

    public Flux<? extends Asset> getAssets(AssetType type, Collection<?> ids) {
        GenericReactiveCrudService<? extends PatrolConfigEntity, String> service =
                switch (PatrolAssetType.valueOf(type.getId())) {
                    case patrolCategory -> categories;
                    case patrolPoint -> points;
                    case patrolStandard -> standards;
                    case patrolPost -> posts;
                    case patrolRoster -> rosters;
                    case patrolPlan -> plans;
                };
        if (ids.isEmpty()) return Flux.empty();
        return service.createQuery()
                .in("id", ids)
                .fetch()
                .map(e -> new DefaultAsset(e.getId(), e.getName(), type).withEntity(e));
    }
}
