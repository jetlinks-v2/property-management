package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;
import javax.persistence.Table;

/** 应用/月导入的并发版本；事务内 CAS 防止两次整月替换交错产生双份在班记录。 */
@Getter
@Setter
@Table(name = "pt_roster_month")
public class PatrolRosterMonthEntity extends GenericEntity<String> {
    @Column(nullable = false)
    private long version;
}
