package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairLevelConfigurationTest {
    private RepairConfigurationEntity level() {
        var row = new RepairConfigurationEntity();
        row.setKind("level"); row.setName("紧急");
        row.setConfiguration(new HashMap<>(Map.of("color", "#E5484D", "coefficient", 9)));
        return row;
    }

    @Test void requiresLegacyNameAndColor() {
        var row = level(); row.setName(" ");
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(row));
        row.setName("紧".repeat(65));
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(row));
        row.setName("紧急"); row.getConfiguration().put("color", " ");
        assertEquals("error.repair.level_color_required", assertThrows(BusinessException.class,
            () -> RepairConfigurationRules.validate(row)).getMessage());
    }

    @Test void requiresExactCoefficientWithoutImplicitDefaultOrTruncation() {
        for (Object value : new Object[]{"", 0, 1000, 1.5, "wrong"}) {
            assertThrows(BusinessException.class, () -> RepairConfigurationRules.levelCoefficient(value));
        }
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.levelCoefficient(null));
        assertEquals(1, RepairConfigurationRules.levelCoefficient(1));
        assertEquals(999, RepairConfigurationRules.levelCoefficient(999));
    }

    @Test void switchDefaultsAndStaleRoleAreNormalized() {
        var row = level(); row.getConfiguration().put("auditRoleId", "old-role");
        RepairConfigurationRules.validate(row);
        assertEquals(false, row.getConfiguration().get("auditRequired"));
        assertEquals(false, row.getConfiguration().get("thirdParty"));
        assertFalse(row.getConfiguration().containsKey("auditRoleId"));
        row.getConfiguration().put("thirdParty", "true");
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(row));
    }

    @Test void auditRequiresRoleAndKeepsSelectedRole() {
        var row = level(); row.getConfiguration().put("auditRequired", true);
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(row));
        row.getConfiguration().put("auditRoleId", "reviewer");
        RepairConfigurationRules.validate(row);
        assertEquals("reviewer", row.getConfiguration().get("auditRoleId"));
    }

    @Test void uniqueKeyComesFromCoefficientAndIsClearedForOtherDictionaries() {
        var row = level(); row.setLevelCoefficient(99);
        RepairConfigurationRules.validate(row);
        assertEquals(9, row.getLevelCoefficient());
        row.setKind("channel"); RepairConfigurationRules.validate(row);
        assertNull(row.getLevelCoefficient());
    }
}
