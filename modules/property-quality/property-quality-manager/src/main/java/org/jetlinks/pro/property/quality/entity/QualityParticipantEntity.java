package org.jetlinks.pro.property.quality.entity;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;import org.hswebframework.web.crud.annotation.EnableEntityEvent;
/** 当前待办及历史参与关系索引；历史参与不授予资产读取或操作权限。 */
@Getter @Setter @EnableEntityEvent @Table(name="pq_participant",indexes={@Index(name="idx_pq_participant_user",columnList="applicationId,userId,kind,active")})
public class QualityParticipantEntity extends QualityEntity { @Column(length=64)private String workOrderId;@Column(length=64)private String userId;@Column(length=32)private String kind;@Column private Boolean active; }
