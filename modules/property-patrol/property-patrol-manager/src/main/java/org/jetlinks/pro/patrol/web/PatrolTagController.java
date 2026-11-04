package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolTagEntity;
import org.jetlinks.pro.patrol.service.PatrolTagService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolConfigTransferService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 标签查询与整体保存；停用保留历史关联，不暴露物理删除。 */
@RestController
@RequestMapping("/park/patrol/tags")
@Resource(id = "patrol-tag", name = "标签")
public class PatrolTagController extends PatrolWorkbookConfigController<PatrolTagEntity> {
    public PatrolTagController(
            PatrolTagService service, PatrolConfigurationValidator validator, PatrolConfigTransferService transfer) {
        super(service, validator, PatrolAssetType.patrolTag, transfer);
    }
}
