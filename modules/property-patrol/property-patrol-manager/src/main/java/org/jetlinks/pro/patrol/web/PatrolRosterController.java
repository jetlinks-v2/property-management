package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolRosterService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更排班查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/rosters")
@Resource(id = "patrol-roster", name = "巡更排班")
public class PatrolRosterController extends PatrolConfigController<PatrolRosterEntity> {
    public PatrolRosterController(
            PatrolRosterService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolRoster);
    }
}
