package org.jetlinks.pro.property.quality.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.quality.entity.QualityDraftEntity;
/** 品质Draft响应式仓储；权限和状态编排由领域入口负责。 */
@Service public class QualityDraftService extends GenericReactiveCrudService<QualityDraftEntity,String> {}
