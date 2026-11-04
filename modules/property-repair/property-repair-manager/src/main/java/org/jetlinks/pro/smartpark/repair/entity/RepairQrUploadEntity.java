package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 匿名上传回执只属于一个二维码；不能使用任意文件ID或其他二维码的回执创建工单。 */
@Getter @Setter
@Table(name="sp_repair_qr_upload",indexes=@Index(name="idx_repair_qr_upload_expiry",columnList="expiresAt,orderId"))
public class RepairQrUploadEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false) private String qrId;
    @Column(length=64,nullable=false) private String fileId;
    @Column(length=64) private String orderId;
    @Column private Long expiresAt;
}
