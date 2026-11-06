package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationSelfInspectionEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Objects;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 独立自检查询和删除边界，项目授权由公共基础服务的 AssetsHolder 结果统一注入。 */
@Service @RequiredArgsConstructor
public class DecorationSelfInspectionService {
    private final DecorationScope scope;
    private final DecorationSelfInspectionRepository repository;

    /** 仅在申请创建事务内调用；附件继续属于申请，不重新消费回执。 */
    public Mono<Void> capture(DecorationApplicationEntity application) {
        if (!"h5_owner".equals(application.getSource())) return Mono.empty();
        var row = new DecorationSelfInspectionEntity();
        row.setId(DecorationApplicationService.identity(application.getId() + ":self-inspection"));
        row.setDecorationId(application.getId()); row.setApplicationId(application.getApplicationId()); row.setTenantId(application.getTenantId());
        row.setProjectId(application.getProjectId()); row.setProjectName(application.getProjectName());
        row.setSpaceId(application.getSpaceId()); row.setSpaceName(application.getSpaceName());
        row.setNumber("ZJ-" + application.getNumber().substring(3));
        row.setCreatorId(application.getCreatorId()); row.setCreatorName(application.getCreatorName()); row.setCreateTime(application.getCreateTime());
        row.setStatus(application.getDetails().getSelfInspectionStatus()); row.setRemark(application.getDetails().getSelfInspectionRemark());
        row.setFileIds(List.copyOf(application.getDetails().getSelfInspectionFiles()));
        row.setDeleted(false); row.setVersion(1L); row.setModifyTime(row.getCreateTime());
        return repository.insert(row).then();
    }

    private Mono<QueryParamEntity> scoped(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx))
            .map(query -> query.toNestQuery(nest -> nest.and("deleted", false)).getParam());
    }
    public Mono<PagerResult<DecorationSelfInspectionEntity>> query(QueryParamEntity input) {
        return scoped(input).flatMap(query -> QueryHelper.queryPager(query, repository::createQuery));
    }
    public Mono<DecorationSelfInspectionEntity> read(String id) {
        return scoped(QueryParamEntity.newQuery().where("id", id).getParam())
            .flatMap(query -> repository.createQuery().setParam(query).fetchOne())
            .switchIfEmpty(Mono.error(error("not_found", 404)));
    }
    /** 版本条件避免重复删除和并发修改；申请快照、文件和轨迹均不改动。 */
    public Mono<Void> delete(String id, long version) {
        return scope.employee().then(read(id)).flatMap(row -> {
            require(Objects.equals(row.getVersion(), version), "version_conflict");
            return repository.createUpdate().set("deleted", true).set("version", version + 1)
                .set("modifyTime", System.currentTimeMillis()).where("id", id).and("version", version).and("deleted", false)
                .execute().doOnNext(count -> require(count == 1, "version_conflict"));
        }).then();
    }
}
