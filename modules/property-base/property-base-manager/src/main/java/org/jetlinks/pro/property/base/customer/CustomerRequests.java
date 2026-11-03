package org.jetlinks.pro.property.base.customer;

import lombok.Getter;
import lombok.Setter;

/** 客户档案写入契约；账号和个人/企业类型在建档后不可变。 */
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
    }

    @Getter
    @Setter
    public static class Link {
        private String spaceId;
        private String nature;
        private String familyMode;
        private String familyName;
        private String familyId;
        private String familyGroup;
        private Long startTime;
    }

    @Getter
    @Setter
    public static class Create extends Profile {
        private String spaceId;
        private String nature;
        private String familyMode;
        private String familyName;
        private String familyId;
        private String familyGroup;
        private Long startTime;

        public Link link() {
            Link link = new Link();
            link.setSpaceId(spaceId);
            link.setNature(nature);
            link.setFamilyMode(familyMode);
            link.setFamilyName(familyName);
            link.setFamilyId(familyId);
            link.setFamilyGroup(familyGroup);
            link.setStartTime(startTime);
            return link;
        }
    }

    public record End(Long version, Long endTime, String endReason) {}

    public record ImportResult(int count) {}

    public record Family(String id, String name) {}
}
