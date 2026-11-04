package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolTagEntity;
import org.springframework.stereotype.Service;

/** 标签持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolTagService
        extends GenericReactiveCrudService<PatrolTagEntity, String> {
    public PatrolTagService(ReactiveRepository<PatrolTagEntity, String> repository) {
        super(repository);
    }
}
