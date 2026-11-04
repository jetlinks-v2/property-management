package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.jetlinks.pro.patrol.service.PatrolConfigDeletionService;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolWarningEntity;
import org.jetlinks.pro.patrol.service.PatrolWarningService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 异常预警配置查询与整体保存；停用与逻辑删除分离，保留历史关联。 */
@RestController
@RequestMapping("/park/patrol/warnings")
@Resource(id = "patrol-warning", name = "异常预警配置")
public class PatrolWarningController extends PatrolConfigController<PatrolWarningEntity> {
    private final PatrolConfigDeletionService deletion;

    /** 删除需要独立 delete 权限；资产与应用边界由删除服务统一检查。 */
    @DeleteMapping("/{id}")
    @DeleteAction
    @org.jetlinks.pro.assets.annotation.AssetsController(ignore = true)
    public reactor.core.publisher.Mono<Void> delete(@org.springframework.web.bind.annotation.PathVariable String id) {
        return deletion.delete(PatrolAssetType.patrolWarning, id);
    }

    public PatrolWarningController(
            PatrolWarningService service, PatrolConfigurationValidator validator, PatrolConfigDeletionService deletion) {
        super(service, validator, PatrolAssetType.patrolWarning);
        this.deletion = deletion;
    }
}
