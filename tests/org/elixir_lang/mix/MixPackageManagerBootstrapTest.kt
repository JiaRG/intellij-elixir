package org.elixir_lang.mix

import com.intellij.util.execution.ParametersListUtil
import junit.framework.TestCase

class MixPackageManagerBootstrapTest : TestCase() {
    fun testAddsBootstrapBeforeMixTask() {
        assertEquals(
            "do local.hex --force --if-missing , local.rebar --force --if-missing , test --formatter TeamCityExUnitFormatter",
            ParametersListUtil.join(
                withPackageManagerBootstrap(listOf("test", "--formatter", "TeamCityExUnitFormatter"))
            )
        )
    }

    fun testAddsBootstrapInsideExistingDoTaskChain() {
        assertEquals(
            "do local.hex --force --if-missing , local.rebar --force --if-missing , intellij_elixir.debug, test --trace",
            ParametersListUtil.join(
                withPackageManagerBootstrap(listOf("do", "intellij_elixir.debug,", "test", "--trace"))
            )
        )
    }
}
