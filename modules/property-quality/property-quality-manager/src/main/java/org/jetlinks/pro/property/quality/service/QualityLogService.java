package org.jetlinks.pro.property.quality.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.quality.entity.QualityLogEntity;
/** 品质Log响应式仓储；权限和状态编排由领域入口负责。 */
@Service public class QualityLogService extends GenericReactiveCrudService<QualityLogEntity,String> {}
