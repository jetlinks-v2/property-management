package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.jetlinks.pro.patrol.service.PatrolCategoryService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更分类查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/categories")
@Resource(id = "patrol-category", name = "巡更分类")
public class PatrolCategoryController extends PatrolConfigController<PatrolCategoryEntity> {
    public PatrolCategoryController(
            PatrolCategoryService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolCategory);
    }
}
