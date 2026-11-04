package org.jetlinks.pro.property.base.customer;

import lombok.Getter;
import lombok.Setter;

/** 客户资料与空间内性质分别持久化；起止时间由后端记录。 */
public final class CustomerRequests {
    private CustomerRequests() {}

    @Getter
    @Setter
    public static class Profile {
        private String userId;
        private String customerType;
        private String name;
        private String telephone;
        private String email;
        private String address;
        private String certificateType;
        private String certificateNumber;
        private String creditCode;
        private String legalRepresentative;
        private String registeredAddress;
        private Long version;
        // 仅用于编辑当前空间记录，不写入共用客户资料。
        private String relationshipId;
        private Long relationshipVersion;
        private String nature;
    }

    @Getter
    @Setter
    public static class Link {
        private String spaceId;
        private String nature;
    }

    @Getter
    @Setter
    public static class Create extends Profile {
        private String spaceId;

        public Link link() {
            var link = new Link();
            link.setSpaceId(spaceId);
            link.setNature(getNature());
            return link;
        }
    }

    public record ImportResult(int count) {}
}
