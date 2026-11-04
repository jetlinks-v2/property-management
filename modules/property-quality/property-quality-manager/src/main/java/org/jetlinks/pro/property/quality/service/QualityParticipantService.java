package org.jetlinks.pro.property.quality.service;
import org.springframework.stereotype.Service;import org.hswebframework.web.crud.service.GenericReactiveCrudService;import org.jetlinks.pro.property.quality.entity.QualityParticipantEntity;
/** 仅由工作流维护的参与索引仓储。 */
@Service public class QualityParticipantService extends GenericReactiveCrudService<QualityParticipantEntity,String> {}
