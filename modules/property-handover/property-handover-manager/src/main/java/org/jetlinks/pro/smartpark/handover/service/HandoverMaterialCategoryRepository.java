package org.jetlinks.pro.smartpark.handover.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.entity.HandoverMaterialCategoryEntity;
import org.springframework.stereotype.Service;

/** 共享资料分类持久层；权限、引用保护和事务由应用服务统一处理。 */
@Service
public class HandoverMaterialCategoryRepository
        extends GenericReactiveCrudService<HandoverMaterialCategoryEntity, String> {}
