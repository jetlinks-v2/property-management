package org.jetlinks.pro.patrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.CheckResult;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity.CheckItem;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity.ItemType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatrolInspectionTest {
    static CheckItem number(boolean photo) {
        return new CheckItem("pressure", "Pressure", true, List.of(), ItemType.number,
                new BigDecimal("0.2"), new BigDecimal("0.4"), "MPa", List.of(), photo, null);
    }

    @Test
    void numericLimitsAreInclusiveAndOutOfRangeIsAnAcceptedFail() {
        var standard = List.of(number(false));
        PatrolInspection.validateStandard(standard);
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "0.2"))).result());
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "0.400000"))).result());
        assertEquals(CheckResult.fail, PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "0.400001"))).result());
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "NaN"))));
    }

    @Test
    void requiredEvidenceAndTypedAnswerCannotBeBypassed() {
        var standard = List.of(number(true));
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "0.3"))));
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(standard, List.of(new Answer("pressure", "0.3", "photo"))).result());
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(standard, List.of()));
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(standard,
                List.of(new Answer("pressure", "0.3", "photo"), new Answer("pressure", "0.4", "photo"))));
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(standard, List.of(new Answer("unknown", "0.3", "photo"))));
    }

    @Test
    void choicesUseFirstOptionEvenWithoutOrAgainstFormerPassingSelections() {
        var choice = new CheckItem("lock", "Lock", true, List.of("normal", "abnormal"), ItemType.choice,
                null, null, null, List.of("abnormal"), false, null);
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(List.of(choice), List.of(new Answer("lock", "normal"))).result());
        assertEquals(CheckResult.fail, PatrolInspection.evaluate(List.of(choice), List.of(new Answer("lock", "abnormal"))).result());
        var legacy = new CheckItem("lock", "Lock", true, List.of("normal", "abnormal"));
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(List.of(legacy), List.of(new Answer("lock", "normal"))).result());
    }

    @Test
    void textAndImageDoNotAutomaticallyPassAndOptionalImageMayBeOmitted() {
        var text = new CheckItem("note", "Note", true, List.of(), ItemType.text,
                null, null, "Reference", List.of(), false, null);
        var image = new CheckItem("photo", "Photo", false, List.of(), ItemType.image,
                null, null, null, List.of(), false, null);
        assertEquals(CheckResult.not_evaluated, PatrolInspection.evaluate(List.of(text, image), List.of(new Answer("note", "Reference"))).result());
        assertEquals(CheckResult.not_evaluated, PatrolInspection.evaluate(List.of(image), List.of(new Answer("photo", null, "file"))).result());
        assertThrows(BusinessException.class, () -> PatrolInspection.evaluate(List.of(image), List.of(new Answer("photo", "fake-url"))));
    }

    @Test
    void textImageAndOptionalOmissionsDoNotBlockNumericChecksOrHideAnomalies() {
        var text = new CheckItem("note", "Note", true, List.of(), ItemType.text,
                null, null, "Reference", List.of(), false, null);
        var photo = new CheckItem("photo", "Photo", false, List.of(), ItemType.image,
                null, null, null, List.of(), false, null);
        var standard = List.of(number(false), text, photo);
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(standard,
                List.of(new Answer("pressure", "0.4"), new Answer("note", "Checked"))).result());
        assertEquals(CheckResult.fail, PatrolInspection.evaluate(standard,
                List.of(new Answer("pressure", "0.400001"), new Answer("note", "Checked"))).result());
        assertEquals(CheckResult.not_evaluated, PatrolInspection.resultOf(null, new Answer("old", "legacy")));
        assertEquals(CheckResult.not_evaluated, PatrolInspection.resultOf(number(false), new Answer("pressure", "invalid")));
    }

    @Test
    void malformedStandardCannotCreateAmbiguousChecks() {
        var n = number(false);
        assertThrows(BusinessException.class, () -> PatrolInspection.validateStandard(List.of(n, n)));
        var badRange = new CheckItem("n", "N", true, List.of(), ItemType.number,
                BigDecimal.TEN, BigDecimal.ONE, null, List.of(), false, null);
        assertThrows(BusinessException.class, () -> PatrolInspection.validateStandard(List.of(badRange)));
        var badChoice = new CheckItem("c", "C", true, List.of("a"), ItemType.choice,
                null, null, null, List.of("b"), false, null);
        assertThrows(BusinessException.class, () -> PatrolInspection.validateStandard(List.of(badChoice)));
        var emptyChoice = new CheckItem("c", "C", true, List.of());
        assertThrows(BusinessException.class, () -> PatrolInspection.validateStandard(List.of(emptyChoice)));
    }

    @Test
    void inspectionContentAllowsOriginalTwoHundredCharactersAndRejectsLongerInput() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = new CheckItem("content", "项".repeat(200), true, List.of("normal"));
            assertEquals(0, validator.validate(valid).size());
            var invalid = new CheckItem("content", "项".repeat(201), true, List.of("normal"));
            assertEquals(1, validator.validate(invalid).size());
        }
    }

    @Test
    void standardAndWarningNamesKeepOriginalThirtyCharacterLimit() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var entity : List.of(new org.jetlinks.pro.patrol.entity.PatrolStandardEntity(),
                                      new org.jetlinks.pro.patrol.entity.PatrolWarningEntity())) {
                entity.setName("名".repeat(30));
                assertEquals(0, validator.validateProperty(entity, "name").size());
                entity.setName("名".repeat(31));
                assertEquals(1, validator.validateProperty(entity, "name").size());
            }
        }
    }

    @Test
    void deployedJsonWithoutTypeStillDeserializesAsChoice() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var old = mapper.readValue("{\"id\":\"lock\",\"title\":\"Lock\",\"required\":true,\"options\":[\"normal\"]}", CheckItem.class);
        assertEquals(ItemType.choice, old.type());
        assertEquals(List.of(), old.qualifiedOptions());
        assertEquals(CheckResult.pass, PatrolInspection.evaluate(List.of(old), List.of(new Answer("lock", "normal"))).result());
        assertEquals("0.2", mapper.readTree(mapper.writeValueAsString(number(false))).get("lowerLimit").asText());
        assertEquals(true, mapper.readTree(mapper.writeValueAsString(number(false))).get("lowerLimit").isTextual());
    }
}
