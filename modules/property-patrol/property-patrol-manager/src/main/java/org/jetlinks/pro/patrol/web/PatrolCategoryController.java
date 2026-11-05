package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.jetlinks.pro.patrol.service.PatrolCategoryService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolConfigDeletionService;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import reactor.core.publisher.Mono;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更分类查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/categories")
@Resource(id = "patrol-category", name = "巡更分类")
public class PatrolCategoryController extends PatrolConfigController<PatrolCategoryEntity> {
    private final PatrolConfigDeletionService deletion;

    /** 分类单节点逻辑删除；功能权限在入口，资产和引用校验由删除服务负责。 */
    @DeleteMapping("/{id}")
    @DeleteAction
    @AssetsController(ignore = true)
    public Mono<Void> delete(@PathVariable String id) {
        return deletion.delete(PatrolAssetType.patrolCategory, id);
    }

    public PatrolCategoryController(
            PatrolCategoryService service, PatrolConfigurationValidator validator, PatrolConfigDeletionService deletion) {
        super(service, validator, PatrolAssetType.patrolCategory);
        this.deletion = deletion;
    }
}
