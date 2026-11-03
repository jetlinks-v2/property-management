package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.springframework.stereotype.Service;

/** 按持久化任务组分页，时段及执行详情通过 taskGroupId 查询。 */
@Service
public class PatrolTaskGroupService extends GenericReactiveCrudService<PatrolGenerationEntity, String> {
    public PatrolTaskGroupService(ReactiveRepository<PatrolGenerationEntity, String> repository) {
        super(repository);
    }
}
