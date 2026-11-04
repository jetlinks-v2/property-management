package org.jetlinks.pro.property.base.space;

import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.io.Serial;

/** 当前根空间绑定派生的归属，不复制到消费方；leaf 来自完整来源树而非权限裁剪后的子树。 */
@Getter @Setter
public class PropertySpaceInfo implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String id;
    private String name;
    private String parentId;
    private String rootId;
    private String organizationId;
    private String fullName;
    private Boolean leaf;
}
