package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolPlanService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 巡更计划查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/plans")
@Resource(id = "patrol-plan", name = "巡更计划")
public class PatrolPlanController extends PatrolConfigController<PatrolPlanEntity> {
    public PatrolPlanController(PatrolPlanService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolPlan);
    }
}
