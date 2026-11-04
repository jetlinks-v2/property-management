package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.jetlinks.pro.patrol.service.PatrolLevelService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 点位等级查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/levels")
@Resource(id = "patrol-level", name = "点位等级")
public class PatrolLevelController extends PatrolConfigController<PatrolLevelEntity> {
    public PatrolLevelController(
            PatrolLevelService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolLevel);
    }
}
