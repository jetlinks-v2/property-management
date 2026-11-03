package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolPointService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更点位查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/points")
@Resource(id = "patrol-point", name = "巡更点位")
public class PatrolPointController extends PatrolConfigController<PatrolPointEntity> {
    public PatrolPointController(
            PatrolPointService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolPoint);
    }
}
