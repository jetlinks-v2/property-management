package org.jetlinks.pro.patrol.entity;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import javax.persistence.Table;

/** 巡更内部标签，通过应用资产边界授权并以停用保留历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_tag")
@Asset(type = "patrolTag")
public class PatrolTagEntity extends PatrolConfigEntity {
}
