package com.datagovernance.api.security;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主体的归一化语义：静态令牌与 JWT 两条路径必须产出同一种主体。 */
class PrincipalTest {

    @Test
    void normalizesAndDropsUnknownRoles() {
        Principal principal = new Principal("alice@corp", "Alice",
                Set.of("admin", " READER ", "SUPERUSER", ""));
        assertEquals(Set.of("ADMIN", "READER"), principal.roles());
    }

    @Test
    void adminImpliesEveryRole() {
        Principal principal = new Principal("alice@corp", "Alice", Set.of("ADMIN"));
        assertTrue(principal.hasRole("STEWARD"));
        assertTrue(principal.hasRole("EDITOR"));
        assertTrue(principal.hasRole("READER"));
    }

    @Test
    void nonAdminOnlyHasItsOwnRoles() {
        Principal principal = new Principal("bob@corp", "Bob", Set.of("READER"));
        assertFalse(principal.hasRole("EDITOR"));
        assertTrue(principal.hasRole("READER"));
    }

    @Test
    void fallsBackToUnknownIdentity() {
        assertEquals("unknown", new Principal(null, null, Set.of()).id());
        assertEquals("unknown", new Principal("  ", "  ", Set.of()).id());
    }

    @Test
    void displayNameFallsBackToIdentity() {
        assertEquals("alice@corp", new Principal("alice@corp", "", Set.of()).displayName());
        assertEquals("Alice", new Principal("alice@corp", "Alice", Set.of()).displayName());
    }
}
