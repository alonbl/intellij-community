/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o. and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.jetbrains.packagesearch.intellij.plugin.extensibility

import com.intellij.buildsystem.model.unified.UnifiedDependency
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtil
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.PackageVersion
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
import java.util.concurrent.CompletableFuture

/**
 * Class representing a native [Module] enriched with Package Search data.
 *
 * @param name the name of the module.
 * @param nativeModule the native [Module] it refers to.
 * @param parent the parent [ProjectModule] of this object.
 * @param buildFile The build file used by this module (e.g. `pom.xml` for Maven, `build.gradle` for Gradle).
 * @param projectDir The corresponding directory in the virtual filesystem for this module.
 * @param buildSystemType The type of the build system file used in this module (e.g., 'gradle-kotlin', 'gradle-groovy', etc.)
 * @param moduleType The additional Package Search related data such as project icons, additional localizations and so on.
 * listed in the Dependency Analyzer tool. At the moment the DA only supports Gradle and Maven.
 * @param availableScopes Scopes available for the build system of this module (e.g. `implementation`, `api` for Gradle;
 * `test`, `compile` for Maven).
 * @param dependencyDeclarationCallback Given a [Dependency], it should return the indexes in the build file where given
 * dependency has been declared.
 */
data class ProjectModule @JvmOverloads constructor(
    @NlsSafe val name: String,
    val nativeModule: Module,
    val parent: ProjectModule?,
    val buildFile: VirtualFile?,
    val projectDir: VirtualFile,
    val buildSystemType: BuildSystemType,
    val moduleType: ProjectModuleType,
    val availableScopes: List<String> = emptyList(),
    val dependencyDeclarationCallback: DependencyDeclarationCallback = { _ -> CompletableFuture.completedFuture(null) }
) {

    @Suppress("UNUSED_PARAMETER")
    @Deprecated(
        "Use main constructor",
        ReplaceWith("ProjectModule(name, nativeModule, parent, buildFile, projectDir, buildSystemType, moduleType)")
    )
    @ScheduledForRemoval
    constructor(
        name: String,
        nativeModule: Module,
        parent: ProjectModule,
        buildFile: VirtualFile,
        buildSystemType: BuildSystemType,
        moduleType: ProjectModuleType,
        navigatableDependency: (groupId: String, artifactId: String, version: PackageVersion) -> Navigatable?
    ) : this(name, nativeModule, parent, buildFile, buildFile.parent, buildSystemType, moduleType)

    @Suppress("UNUSED_PARAMETER")
    @Deprecated(
        "Use main constructor",
        ReplaceWith("ProjectModule(name, nativeModule, parent, buildFile, projectDir, buildSystemType, moduleType)")
    )
    @ScheduledForRemoval
    constructor(
        name: String,
        nativeModule: Module,
        parent: ProjectModule,
        buildFile: VirtualFile,
        buildSystemType: BuildSystemType,
        moduleType: ProjectModuleType,
        navigatableDependency: (groupId: String, artifactId: String, version: PackageVersion) -> Navigatable?,
        availableScopes: List<String>
    ) : this(name, nativeModule, parent, buildFile, buildFile.parent, buildSystemType, moduleType, availableScopes)

    fun getBuildFileNavigatableAtOffset(offset: Int): Navigatable? =
        buildFile?.let {
            PsiManager.getInstance(nativeModule.project).findFile(it)?.let { psiFile ->
                PsiUtil.getElementAtOffset(psiFile, offset).takeIf { it != buildFile } as? Navigatable
            }
        }

    @NlsSafe
    fun getFullName(): String =
        parent?.let { it.getFullName() + ":$name" } ?: name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectModule) return false

        if (name != other.name) return false
        if (!nativeModule.isTheSameAs(other.nativeModule)) return false // This can't be automated
        if (parent != other.parent) return false
        if (buildFile?.path != other.buildFile?.path) return false
        if (projectDir.path != other.projectDir.path) return false
        if (buildSystemType != other.buildSystemType) return false
        if (moduleType != other.moduleType) return false
        // if (navigatableDependency != other.navigatableDependency) return false // Intentionally excluded
        if (availableScopes != other.availableScopes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + nativeModule.hashCodeOrZero()
        result = 31 * result + (parent?.hashCode() ?: 0)
        result = 31 * result + (buildFile?.path?.hashCode() ?: 0)
        result = 31 * result + projectDir.path.hashCode()
        result = 31 * result + buildSystemType.hashCode()
        result = 31 * result + moduleType.hashCode()
        // result = 31 * result + navigatableDependency.hashCode() // Intentionally excluded
        result = 31 * result + availableScopes.hashCode()
        return result
    }
}

internal fun Module.isTheSameAs(other: Module) =
    runCatching { moduleFilePath == other.moduleFilePath && name == other.name }
        .getOrDefault(false)

private fun Module.hashCodeOrZero() =
    runCatching { moduleFilePath.hashCode() + 31 * name.hashCode() }
        .getOrDefault(0)

typealias DependencyDeclarationCallback = (Dependency) -> CompletableFuture<DependencyDeclarationIndexes?>

/**
 * Container class for declaration coordinates for a dependency in a build file. \
 * Example for Gradle:
 * ```
 *    implementation("io.ktor:ktor-server-cio:2.0.0")
 * // ▲               ▲                       ▲
 * // |               ∟ coordinatesStartIndex |
 * // ∟ wholeDeclarationStartIndex            ∟ versionStartIndex
 * //
 * ```
 * Example for Maven:
 * ```
 *      <dependency>
 * //    ▲ wholeDeclarationStartIndex
 *          <groupId>io.ktor</groupId>
 * //                ▲ coordinatesStartIndex
 *          <artifactId>ktor-server-cio</artifactId>
 *          <version>2.0.0</version>
 * //                ▲ versionStartIndex
 *      </dependency>
 * ```
 * @param wholeDeclarationStartIndex index of the first character where the whole declarations starts.
 *
 */
@Serializable
data class DependencyDeclarationIndexes(
    val wholeDeclarationStartIndex: Int,
    val coordinatesStartIndex: Int,
    val versionStartIndex: Int?
)

data class UnifiedDependencyKey(val scope: String, val groupId: String, val module: String)

val UnifiedDependency.key: UnifiedDependencyKey?
    get() {
        return UnifiedDependencyKey(scope ?: return null, coordinates.groupId!!, coordinates.artifactId ?: return null)
    }

fun UnifiedDependency.asDependency(): Dependency? {
    return Dependency(
        scope = scope ?: return null,
        groupId = coordinates.groupId ?: return null,
        artifactId = coordinates.artifactId ?: return null,
        version = coordinates.version ?: return null
    )
}

data class Dependency(val scope: String, val groupId: String, val artifactId: String, val version: String) {

    override fun toString() = "$scope(\"$groupId:$artifactId:$version\")"
}
