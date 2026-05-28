package org.elixir_lang.mix.project

import com.intellij.projectImport.ProjectImportBuilder
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.project._import.Builder

class OpenProcessorTest : PlatformTestCase() {

    fun testCanOpenProjectWithMixExsFile() {
        val mixExsContent = """
            defmodule TestApp.MixProject do
              use Mix.Project

              def project do
                [app: :test_app, version: "0.1.0"]
              end
            end
        """.trimIndent()

        val projectDir = myFixture.tempDirFixture.findOrCreateDir("test_project")
        myFixture.tempDirFixture.createFile("test_project/mix.exs", mixExsContent)
        val mixExsFile = projectDir.findChild("mix.exs")!!

        val openProcessor = OpenProcessor()

        // Test canOpenProject with the mix.exs file directly
        assertTrue("Should be able to open project with mix.exs file", openProcessor.canOpenProject(mixExsFile))
    }

    fun testCanOpenProjectWithDirectoryContainingMixExs() {
        val mixExsContent = """
            defmodule TestApp.MixProject do
              use Mix.Project

              def project do
                [app: :test_app, version: "0.1.0"]
              end
            end
        """.trimIndent()

        val projectDir = myFixture.tempDirFixture.findOrCreateDir("test_project_dir")
        myFixture.tempDirFixture.createFile("test_project_dir/mix.exs", mixExsContent)

        val openProcessor = OpenProcessor()

        // Test canOpenProject with the directory containing mix.exs
        assertTrue("Should be able to open project from directory containing mix.exs", openProcessor.canOpenProject(projectDir))
    }

    fun testCannotOpenProjectWithoutMixExs() {
        val projectDir = myFixture.tempDirFixture.findOrCreateDir("empty_project")
        myFixture.tempDirFixture.createFile("empty_project/some_file.txt", "hello")

        val openProcessor = OpenProcessor()

        // Should not be able to open a directory without mix.exs
        assertFalse("Should not be able to open project without mix.exs", openProcessor.canOpenProject(projectDir))
    }

    fun testBuilderSetProjectRootAndScan() {
        val mixExsContent = """
            defmodule TestApp.MixProject do
              use Mix.Project

              def project do
                [app: :test_app, version: "0.1.0"]
              end
            end
        """.trimIndent()

        val projectDir = myFixture.tempDirFixture.findOrCreateDir("builder_test_project")
        myFixture.tempDirFixture.createFile("builder_test_project/mix.exs", mixExsContent)

        val builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(Builder::class.java)
        builder.cleanup()

        try {
            // Set project root - this is what doQuickImport does
            builder.setProjectRoot(projectDir)

            // Get list triggers the deferred scan
            val foundApps = builder.list

            // Should find the OTP app
            assertFalse("Builder should find OTP apps after setProjectRoot and getList", foundApps.isEmpty())

            val rootApp = foundApps.find { it.name == "test_app" }
            assertNotNull("Should find the test_app OTP app", rootApp)
        } finally {
            builder.cleanup()
        }
    }

    fun testBuilderAssignsPathBasedModuleNamesForDuplicateOtpAppNames() {
        val mixExsContent = """
            defmodule Emqx.MixProject do
              use Mix.Project

              def project do
                [app: :emqx, version: "0.1.0"]
              end
            end
        """.trimIndent()

        val projectDir = myFixture.tempDirFixture.findOrCreateDir("umbrella")
        myFixture.tempDirFixture.createFile("umbrella/mix.exs", mixExsContent)
        myFixture.tempDirFixture.findOrCreateDir("umbrella/apps/emqx")
        myFixture.tempDirFixture.createFile("umbrella/apps/emqx/mix.exs", mixExsContent)

        val builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(Builder::class.java)
        builder.cleanup()

        try {
            builder.setProjectRoot(projectDir)

            val foundApps = builder.list
            val rootApp = foundApps.find { it.root.path == projectDir.path }
            val childApp = foundApps.find { it.root.path.endsWith("/apps/emqx") }

            assertNotNull("Should find root emqx OTP app", rootApp)
            assertNotNull("Should find apps/emqx OTP app", childApp)
            assertEquals("Root app should keep its OTP app name", "emqx", rootApp!!.moduleName)
            assertEquals(
                "Child app with duplicate OTP app name should use path-disambiguated module name",
                "emqx-apps-emqx",
                childApp!!.moduleName
            )
            assertTrue(
                "Import list text should use the disambiguated module name",
                childApp.toString().startsWith("emqx-apps-emqx ")
            )
        } finally {
            builder.cleanup()
        }
    }

    fun testSupportedExtensions() {
        val openProcessor = OpenProcessor()

        // Verify the supported extension is mix.exs
        assertTrue("Should support mix.exs extension", openProcessor.supportedExtensions.contains("mix.exs"))
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/mix/project"
}
