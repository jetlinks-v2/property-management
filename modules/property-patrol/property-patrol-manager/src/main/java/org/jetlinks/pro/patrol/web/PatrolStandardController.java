package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.jetlinks.pro.patrol.service.PatrolConfigDeletionService;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolConfigTransferService;
import org.jetlinks.pro.patrol.service.PatrolStandardService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更标准查询与整体保存；停用与逻辑删除分离，保留历史关联。 */
@RestController
@RequestMapping("/park/patrol/standards")
@Resource(id = "patrol-standard", name = "巡更标准")
public class PatrolStandardController extends PatrolWorkbookConfigController<PatrolStandardEntity> {
    private final PatrolConfigDeletionService deletion;

    /** 删除需要独立 delete 权限；资产与应用边界由删除服务统一检查。 */
    @DeleteMapping("/{id}")
    @DeleteAction
    @org.jetlinks.pro.assets.annotation.AssetsController(ignore = true)
    public reactor.core.publisher.Mono<Void> delete(@org.springframework.web.bind.annotation.PathVariable String id) {
        return deletion.delete(PatrolAssetType.patrolStandard, id);
    }

    public PatrolStandardController(
            PatrolStandardService service, PatrolConfigurationValidator validator, PatrolConfigTransferService transfer, PatrolConfigDeletionService deletion) {
        super(service, validator, PatrolAssetType.patrolStandard, transfer);
        this.deletion = deletion;
    }
}
