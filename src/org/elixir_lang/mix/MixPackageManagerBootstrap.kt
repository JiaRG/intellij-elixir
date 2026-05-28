package org.elixir_lang.mix

internal fun withPackageManagerBootstrap(mixArguments: List<String>): List<String> {
    val bootstrap = INSTALL_HEX_CLI + COMMA + INSTALL_REBAR_CLI

    return if (mixArguments.isEmpty()) {
        listOf("do") + bootstrap
    } else if (mixArguments.firstOrNull() == "do") {
        listOf("do") + bootstrap + COMMA + mixArguments.drop(1)
    } else {
        listOf("do") + bootstrap + COMMA + mixArguments
    }
}
