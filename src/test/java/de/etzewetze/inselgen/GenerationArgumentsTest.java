package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GenerationArgumentsTest {
    private GenerationArguments parse(String... args) {
        return GenerationArguments.parse(args, 50, 500, () -> 987654321L);
    }

    @Test
    void omittedSeedUsesFreshSeedAndNaturalStyle() {
        assertEquals(new GenerationArguments(50, 987654321L, IslandStyle.NATUERLICH), parse("50"));
        assertEquals(500, parse("500").size());
    }

    @Test
    void acceptsSeedAndStyleInEitherOrderIncludingSignedSeeds() {
        assertEquals(new GenerationArguments(200, -731L, IslandStyle.BERGIG), parse("200", "-731", "bergig"));
        assertEquals(new GenerationArguments(200, Long.MIN_VALUE, IslandStyle.FLACH), parse("200", "flach", Long.toString(Long.MIN_VALUE)));
        assertEquals(IslandStyle.BERGIG, parse("150", "BERGIG").style());
    }

    @ParameterizedTest
    @ValueSource(strings = {"49", "501", "0", "-50", "abc", "NaN", "1.5", "2147483648"})
    void rejectsInvalidSizes(String value) {
        assertThrows(IllegalArgumentException.class, () -> parse(value));
    }

    @Test
    void rejectsDuplicateOrUnknownOptionsAndHonorsConfiguredLimits() {
        assertThrows(IllegalArgumentException.class, () -> parse("100", "bergig", "flach"));
        assertThrows(IllegalArgumentException.class, () -> parse("100", "1", "2"));
        assertThrows(IllegalArgumentException.class, () -> parse("100", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> parse("100", "9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> parse());
        assertThrows(IllegalArgumentException.class, () -> GenerationArguments.parse(new String[] {"50"}, 100, 300, () -> 1));
    }

    @Test
    void invalidConfigurationCannotRemoveHardLimits() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(1, 500, 1000, true, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(50, 501, 1000, true, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(200, 100, 1000, true, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GenerationSettings(50, 500, 0, true, true, true, true));
    }
}
