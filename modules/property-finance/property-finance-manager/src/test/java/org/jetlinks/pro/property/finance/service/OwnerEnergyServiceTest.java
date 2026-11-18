package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Map;
import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OwnerEnergyServiceTest {
    @Test void depositRejectsNonMoneyPrecisionAndOutOfRangeWithoutRounding() {
        for (String input : new String[]{"0", "0.99", "-1", "1.001", "1e3", "01", "999999.01", "1000000", "NaN", ""})
            assertThrows(RuntimeException.class, () -> OwnerEnergyService.validateAmount(new OwnerEnergyService.DepositInput("setting", input)), input);
        assertEquals("1.00",OwnerEnergyService.validateAmount(new OwnerEnergyService.DepositInput("setting","1")));
        assertEquals("100.10",OwnerEnergyService.validateAmount(new OwnerEnergyService.DepositInput("setting","100.1")));
        assertEquals("999999.00",OwnerEnergyService.validateAmount(new OwnerEnergyService.DepositInput("setting","999999")));
    }

    @Test void depositRequiresSelectedCustomerAndEffectiveEnergyStandard() {
        var row = new HouseSettingEntity(); row.setEffectiveStartDate("2026-01-01"); row.setEffectiveEndDate("2026-12-31");
        row.setSnapshotJson(com.alibaba.fastjson.JSON.toJSONString(Map.of("standard",Map.of("billingMethod","WATER"),
            "customers",java.util.List.of(Map.of("customerId","current-owner","relationshipId","current-link")))));
        assertTrue(OwnerEnergyService.eligible(row,"current-owner","current-link",LocalDate.of(2026,11,18)));
        assertFalse(OwnerEnergyService.eligible(row,"another-owner","current-link",LocalDate.of(2026,11,18)));
        assertFalse(OwnerEnergyService.eligible(row,"current-owner","renewed-link",LocalDate.of(2026,11,18)));
        assertFalse(OwnerEnergyService.eligible(row,"current-owner","current-link",LocalDate.of(2025,12,31)));
        assertFalse(OwnerEnergyService.eligible(row,"current-owner","current-link",LocalDate.of(2027,1,1)));
    }

    @Test void foreignRoomStopsBeforeReadingSettingsOrAccounts() {
        var houses=mock(OwnerHouseSource.class); var options=mock(PrepayOptionsService.class); var finance=mock(OwnerFinanceService.class);
        when(houses.require("project","foreign-room")).thenReturn(Mono.error(FinanceScope.error("owner_room_denied",403)));
        var service=new OwnerEnergyService(houses,options,finance);
        StepVerifier.create(service.quote("project","foreign-room",new OwnerEnergyService.DepositInput("setting","100")))
            .expectErrorMatches(e -> e instanceof org.hswebframework.web.exception.BusinessException b && b.getStatus()==403).verify();
        verifyNoInteractions(options,finance);
    }
}
