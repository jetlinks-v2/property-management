package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.jetlinks.pro.patrol.service.PatrolConfigDeletionService;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.jetlinks.pro.patrol.service.PatrolLevelService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.jetlinks.pro.patrol.service.PatrolConfigTransferService;
import org.jetlinks.pro.patrol.service.PatrolConfigHistoryService;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Mono;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 点位等级查询与整体保存；停用与逻辑删除分离，保留历史关联。 */
@RestController
@RequestMapping("/park/patrol/levels")
@Resource(id = "patrol-level", name = "点位等级")
public class PatrolLevelController extends PatrolWorkbookConfigController<PatrolLevelEntity> {
    private final PatrolLevelService levels;
    private final PatrolConfigHistoryService history;
    private final PatrolConfigDeletionService deletion;

    /** 删除需要独立 delete 权限；资产与应用边界由删除服务统一检查。 */
    @DeleteMapping("/{id}")
    @DeleteAction
    @org.jetlinks.pro.assets.annotation.AssetsController(ignore = true)
    public reactor.core.publisher.Mono<Void> delete(@org.springframework.web.bind.annotation.PathVariable String id) {
        return deletion.delete(PatrolAssetType.patrolLevel, id);
    }

    public PatrolLevelController(
            PatrolLevelService service, PatrolConfigurationValidator validator, PatrolConfigTransferService transfer, PatrolConfigHistoryService history, PatrolConfigDeletionService deletion) {
        super(service, validator, PatrolAssetType.patrolLevel, transfer);
        this.levels = service; this.history = history; this.deletion = deletion;
    }

    @Override protected Mono<PatrolLevelEntity> created(PatrolLevelEntity entity) {
        return history.created(PatrolAssetType.patrolLevel, entity);
    }
    @Override protected Mono<PatrolLevelEntity> persistUpdate(PatrolLevelEntity old, PatrolLevelEntity entity) {
        return history.update(levels, PatrolAssetType.patrolLevel, old, entity);
    }

    /** 历史沿等级 read 资产权限并固定当前应用，不增加历史记录写接口或新授权项。 */
    @PostMapping("/{id}/history/_query")
    @QueryAction
    @AssetsController(ignore = true)
    public Mono<PagerResult<PatrolConfigHistoryEntity>> history(@PathVariable String id, @RequestBody QueryParamEntity query) {
        return history.history(PatrolAssetType.patrolLevel, id, query);
    }
}
