package omp.vm.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which ABI this process is, and the one question a download cannot afford to get wrong.
 *
 * The disagreement these cover is a real one: a device can advertise one ABI and be executing
 * another through a translation layer, and `os.arch` answers about the CPU while
 * `Build.SUPPORTED_ABIS` answers about the process. Both are spelled out here rather than left in a
 * KDoc, because the next person to change the order of two lines here is choosing which device
 * gets the wrong 55 MB.
 */
class AbiTest {

    @Test
    fun theSupportedAbisWinOverOsArch() {
        // An x86_64 device running this app's arm64 code, and the other way round: the list is the
        // answer that matters, because it is the ABI whose ELF binaries the loader will take.
        assertEquals(Abi.ARM64, Abi.detect("x86_64", listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(Abi.X86_64, Abi.detect("aarch64", listOf("x86_64", "x86")))
    }

    @Test
    fun osArchIsAskedOnlyWhenThereIsNoList() {
        assertEquals(Abi.ARM64, Abi.detect("aarch64", emptyList()))
        assertEquals(Abi.ARMEABI_V7A, Abi.detect("armv7l", emptyList()))
        assertEquals(Abi.X86_64, Abi.detect("amd64", emptyList()))
        assertEquals(Abi.X86, Abi.detect("i686", emptyList()))
    }

    @Test
    fun anArchitectureThisAppDoesNotKnowIsNullRatherThanAGuess() {
        assertNull(Abi.detect("riscv64", listOf("riscv64")))
        assertNull(Abi.detect(null, emptyList()))
        // A list whose first entry is unknown falls through to the next known one, which is the
        // ABI the package manager would have installed this app for.
        assertEquals(Abi.X86_64, Abi.detect("aarch64", listOf("mips64", "x86_64")))
    }

    @Test
    fun a32BitAbiRefusesTheAgentInOneLine() {
        for (abi in listOf(Abi.ARMEABI_V7A, Abi.X86)) {
            assertFalse(abi.abiName, abi.hasAgent)
            assertEquals(abi.abiName, Abi.NO_32_BIT_AGENT, abi.agentRefusal)
        }
        assertTrue(Abi.ARM64.hasAgent)
        assertTrue(Abi.X86_64.hasAgent)
        assertNull(Abi.ARM64.agentRefusal)
        assertNull(Abi.X86_64.agentRefusal)
    }

    @Test
    fun theAbiNamesAreTheOnesAndroidUses() {
        assertEquals("arm64-v8a", Abi.ARM64.abiName)
        assertEquals("armeabi-v7a", Abi.ARMEABI_V7A.abiName)
        assertEquals("x86", Abi.X86.abiName)
        assertEquals("x86_64", Abi.X86_64.abiName)
        assertEquals(Abi.X86_64, Abi.of("x86_64"))
        assertNull(Abi.of("mips"))
    }
}
