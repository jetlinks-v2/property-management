package org.jetlinks.pro.property.base.user;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.io.Serial;
import java.util.List;

/** 公共只读用户身份；来源用户 ID 与 SaaS 一致，不向业务系统暴露持久化或审计对象。 */
@Getter @Setter
public class PropertyUserInfo implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String id;
    private String name;
    private String username;
    /** 供已授权业务保存登记人电话快照，不回写来源账号。 */
    private String telephone;
    private Byte status;
    private List<String> identities = List.of();
    public boolean isEmployee() { return identities.contains("employee"); }
}
