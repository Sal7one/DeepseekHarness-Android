package io.github.jackwener.dsh.core.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAccessTest {
    @Test fun versionGate() {
        assertTrue(ShellAccess.atLeast("13.6.0.r1086.2650830c", 13, 6))
        assertTrue(ShellAccess.atLeast("14.0.1", 13, 6))
        assertFalse(ShellAccess.atLeast("13.5.4.r1049.0e53409", 13, 6))
        assertFalse(ShellAccess.atLeast("", 13, 6))
    }
}
