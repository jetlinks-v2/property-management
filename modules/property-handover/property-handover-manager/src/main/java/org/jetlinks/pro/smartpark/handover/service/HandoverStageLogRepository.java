package org.jetlinks.pro.smartpark.handover.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.entity.HandoverStageLogEntity;
import org.springframework.stereotype.Service;

@Service
public class HandoverStageLogRepository
        extends GenericReactiveCrudService<HandoverStageLogEntity, String> {}
