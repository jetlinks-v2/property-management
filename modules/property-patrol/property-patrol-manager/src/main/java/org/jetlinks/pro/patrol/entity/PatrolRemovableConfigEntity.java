package org.jetlinks.pro.patrol.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;

/** 删除与停用分离；空删除时间兼容已持久化配置，客户端不能设置或清除删除标记。 */
@Getter
@Setter
public abstract class PatrolRemovableConfigEntity extends PatrolConfigEntity {
    @Column
    @JsonIgnore
    private Long deletedTime;
}
