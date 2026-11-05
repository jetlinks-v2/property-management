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
import org.jetlinks.pro.patrol.service.PatrolRosterService;
import org.jetlinks.pro.patrol.service.PatrolStandardService;
import org.jetlinks.pro.patrol.service.PatrolAttachmentService;
import org.springframework.stereotype.Component;
import org.jetlinks.pro.patrol.service.PatrolLevelService;
import org.jetlinks.pro.patrol.service.PatrolTagService;
import org.jetlinks.pro.patrol.service.PatrolWarningService;

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
    private final PatrolRosterService rosters;
    private final PatrolPlanService plans;
    private final PatrolAttachmentService attachments;

    private final PatrolLevelService levels;
    private final PatrolTagService tags;
    private final PatrolWarningService warnings;

    public List<? extends AssetType> getTypes() {
        return List.of(PatrolAssetType.values());
    }

    public Flux<? extends Asset> getAssets(AssetType type, Collection<?> ids) {
        var service = service(PatrolAssetType.valueOf(type.getId()));
        if (ids.isEmpty()) return Flux.empty();
        return service.createQuery()
                .in("id", ids)
                .fetch()
                .map(e -> new DefaultAsset(e.getId(), e.getName(), type).withEntity(e));
    }
    /** 资产约束 owner 读取自有配置，不能在这里再次调用 AssetsHolder 造成递归。 */
    public GenericReactiveCrudService<? extends PatrolConfigEntity, String> service(PatrolAssetType type) {
        return
                switch (type) {
                    case patrolLevel -> levels;
                    case patrolTag -> tags;
                    case patrolWarning -> warnings;
                    case patrolCategory -> categories;
                    case patrolPoint -> points;
                    case patrolStandard -> standards;
                    case patrolRoster -> rosters;
                    case patrolPlan -> plans;
                    case patrolAttachment -> attachments;
                };
    }

}
