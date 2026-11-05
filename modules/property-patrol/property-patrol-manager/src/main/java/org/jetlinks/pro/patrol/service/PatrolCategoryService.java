package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.springframework.stereotype.Service;

/** 巡更分类持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolCategoryService
        extends PatrolRemovableConfigService<PatrolCategoryEntity> {
    public PatrolCategoryService(ReactiveRepository<PatrolCategoryEntity, String> repository) {
        super(repository);
    }
}
