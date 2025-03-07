// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")

package org.jetbrains.intellij.build

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.impl.BaseLayout
import org.jetbrains.intellij.build.impl.LibraryPackMode
import org.jetbrains.intellij.build.kotlin.KotlinPluginBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.function.BiConsumer

private val JAVA_IDE_API_MODULES: List<String> = java.util.List.of(
  "intellij.xml.dom",
  "intellij.platform.uast.tests",
  "intellij.jsp.base"
)

private val JAVA_IDE_IMPLEMENTATION_MODULES: List<String> = java.util.List.of(
  "intellij.xml.dom.impl",
  "intellij.tools.testsBootstrap"
)

private val BASE_CLASS_VERSIONS = persistentHashMapOf(
  "" to "11",
  "lib/idea_rt.jar" to "1.6",
  "lib/forms_rt.jar" to "1.6",
  "lib/annotations.jar" to "1.6",
  // JAR contains class files for Java 1.8 and 11 (several modules packed into it)
  "lib/util.jar!/com/intellij/serialization/" to "1.8",
  "lib/util_rt.jar" to "1.6",
  "lib/external-system-rt.jar" to "1.6",
  "plugins/coverage/lib/coverage_rt.jar" to "1.6",
  "plugins/javaFX/lib/rt/sceneBuilderBridge.jar" to "11",
  "plugins/junit/lib/junit-rt.jar" to "1.6",
  "plugins/junit/lib/junit5-rt.jar" to "1.8",
  "plugins/gradle/lib/gradle-tooling-extension-api.jar" to "1.6",
  "plugins/gradle/lib/gradle-tooling-extension-impl.jar" to "1.6",
  "plugins/maven/lib/maven-server-api.jar" to "1.8",
  "plugins/maven/lib/maven2-server.jar" to "1.8",
  "plugins/maven/lib/maven3-server-common.jar" to "1.8",
  "plugins/maven/lib/maven30-server.jar" to "1.8",
  "plugins/maven/lib/maven3-server.jar" to "1.8",
  "plugins/maven/lib/artifact-resolver-m2.jar" to "1.6",
  "plugins/maven/lib/artifact-resolver-m3.jar" to "1.6",
  "plugins/maven/lib/artifact-resolver-m31.jar" to "1.6",
  "plugins/xpath/lib/rt/xslt-rt.jar" to "1.6",
  "plugins/xslt-debugger/lib/xslt-debugger-rt.jar" to "1.6",
  "plugins/xslt-debugger/lib/rt/xslt-debugger-impl-rt.jar" to "1.8",
)

/**
 * Base class for all editions of IntelliJ IDEA
 */
abstract class BaseIdeaProperties : JetBrainsProductProperties() {
  companion object {
    @Suppress("SpellCheckingInspection")
    @JvmStatic
    val BUNDLED_PLUGIN_MODULES: PersistentList<String> = persistentListOf(
      "intellij.java.plugin",
      "intellij.java.ide.customization",
      "intellij.copyright",
      "intellij.properties",
      "intellij.terminal",
      "intellij.emojipicker",
      "intellij.textmate",
      "intellij.editorconfig",
      "intellij.settingsRepository",
      "intellij.settingsSync",
      "intellij.configurationScript",
      "intellij.yaml",
      "intellij.tasks.core",
      "intellij.repository.search",
      "intellij.maven.model",
      "intellij.maven",
      "intellij.packageSearch",
      "intellij.gradle",
      "intellij.gradle.dependencyUpdater",
      "intellij.android.gradle.dsl",
      "intellij.gradle.java",
      "intellij.gradle.java.maven",
      "intellij.vcs.git",
      "intellij.vcs.svn",
      "intellij.vcs.hg",
      "intellij.vcs.github",
      "intellij.groovy",
      "intellij.junit",
      "intellij.testng",
      "intellij.xpath",
      "intellij.xslt.debugger",
      "intellij.android.plugin",
      "intellij.android.design-plugin",
      "intellij.javaFX.community",
      "intellij.java.i18n",
      "intellij.ant",
      "intellij.java.guiForms.designer",
      "intellij.java.byteCodeViewer",
      "intellij.java.coverage",
      "intellij.java.decompiler",
      "intellij.devkit",
      "intellij.eclipse",
      "intellij.platform.langInjection",
      "intellij.java.debugger.streams",
      "intellij.android.smali",
      "intellij.completionMlRanking",
      "intellij.completionMlRankingModels",
      "intellij.statsCollector",
      "intellij.sh",
      "intellij.markdown",
      "intellij.webp",
      "intellij.grazie",
      "intellij.featuresTrainer",
      "intellij.vcs.git.featuresTrainer",
      "intellij.lombok",
      "intellij.searchEverywhereMl",
      "intellij.platform.tracing.ide",
      "intellij.toml",
      KotlinPluginBuilder.MAIN_KOTLIN_PLUGIN_MODULE,
      "intellij.keymap.eclipse",
      "intellij.keymap.visualStudio",
      "intellij.keymap.netbeans",
    )

    @JvmStatic
    val CE_CLASS_VERSIONS: PersistentMap<String, String> = BASE_CLASS_VERSIONS.putAll(persistentHashMapOf(
      "plugins/java/lib/jshell-frontend.jar" to "9",
      "plugins/java/lib/sa-jdwp" to "",  // ignored
      "plugins/java/lib/rt/debugger-agent.jar" to "1.6",
      "plugins/Groovy/lib/groovy-rt.jar" to "1.6",
      "plugins/Groovy/lib/groovy-constants-rt.jar" to "1.6",
    ))
  }

  init {
    productLayout.mainJarName = "idea.jar"

    productLayout.withAdditionalPlatformJar(BaseLayout.APP_JAR, "intellij.java.ide.resources")

    productLayout.addPlatformCustomizer(BiConsumer { layout, _ ->
      for (name in JAVA_IDE_API_MODULES) {
        if (!productLayout.productApiModules.contains(name)) {
          layout.withModule(name)
        }
      }
      for (moduleName in listOf(
        "intellij.java.testFramework",
        "intellij.platform.testFramework.core",
        "intellij.platform.testFramework.common",
        "intellij.platform.testFramework.junit5",
        "intellij.platform.testFramework",
      )) {
        if (!productLayout.productApiModules.contains(moduleName)) {
          layout.withModule(moduleName, "testFramework.jar")
        }
      }
      for (name in JAVA_IDE_IMPLEMENTATION_MODULES) {
        if (!productLayout.productImplementationModules.contains(name)) {
          layout.withModule(name)
        }
      }
      //todo currently intellij.platform.testFramework included into idea.jar depends on this jar so it cannot be moved to java plugin
      layout.withModule("intellij.java.rt", "idea_rt.jar")
      // for compatibility with users' projects which take these libraries from IDEA installation
      layout.withProjectLibrary("jetbrains-annotations", LibraryPackMode.STANDALONE_SEPARATE_WITHOUT_VERSION_NAME)
      // for compatibility with users projects which refer to IDEA_HOME/lib/junit.jar
      layout.withProjectLibrary("JUnit3", LibraryPackMode.STANDALONE_SEPARATE_WITHOUT_VERSION_NAME)
      layout.withProjectLibrary("commons-net")

      layout.withoutProjectLibrary("Ant")
      // there is a patched version of the org.gradle.api.JavaVersion class placed into the Gradle plugin classpath as "rt" jar
      // to avoid class linkage conflicts "Gradle" library is placed into the 'lib' directory of the Gradle plugin layout so we need to exclude it from the platform layout explicitly
      // TODO should be used as regular project library when the issue will be fixed at the Gradle tooling api side https://github.com/gradle/gradle/issues/8431 and the patched class will be removed
      layout.withoutProjectLibrary("Gradle")

      //this library is placed into subdirectory of 'lib' directory in Android plugin layout, so we need to exclude it from the platform layout explicitly
      layout.withoutProjectLibrary("layoutlib")
    })

    productLayout.compatiblePluginsToIgnore = java.util.List.of(
      "intellij.java.plugin",
    )
    additionalModulesToCompile = java.util.List.of("intellij.tools.jps.build.standalone")
    modulesToCompileTests = java.util.List.of("intellij.platform.jps.build")

    isAntRequired = true
  }

  override fun copyAdditionalFiles(context: BuildContext, targetDirectory: String) {
    val targetDir = Path.of(targetDirectory)
    // for compatibility with users projects which refer to IDEA_HOME/lib/annotations.jar
    Files.move(targetDir.resolve("lib/annotations-java5.jar"), targetDir.resolve("lib/annotations.jar"),
               StandardCopyOption.REPLACE_EXISTING)
  }
}
