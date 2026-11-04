package org.jetlinks.pro.smartpark.repair.service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairQrUploadEntity;
import org.springframework.stereotype.Service;
/** 公开上传回执的内部存储，不提供通用CRUD接口。 */
@Service
public class RepairQrUploadRepository extends GenericReactiveCrudService<RepairQrUploadEntity,String> { }
