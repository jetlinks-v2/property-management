package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolStandardService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更标准查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/standards")
@Resource(id = "patrol-standard", name = "巡更标准")
public class PatrolStandardController extends PatrolConfigController<PatrolStandardEntity> {
    public PatrolStandardController(
            PatrolStandardService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolStandard);
    }
}
