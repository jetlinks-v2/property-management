package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolWarningEntity;
import org.jetlinks.pro.patrol.service.PatrolWarningService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 异常预警配置查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/warnings")
@Resource(id = "patrol-warning", name = "异常预警配置")
public class PatrolWarningController extends PatrolConfigController<PatrolWarningEntity> {
    public PatrolWarningController(
            PatrolWarningService service, PatrolConfigurationValidator validator) {
        super(service, validator, PatrolAssetType.patrolWarning);
    }
}
