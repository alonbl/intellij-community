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

package com.jetbrains.packagesearch.intellij.plugin.gradle

import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.jetbrains.packagesearch.intellij.plugin.extensibility.BuildSystemType
import com.jetbrains.packagesearch.intellij.plugin.extensibility.CoroutineModuleTransformer
import com.jetbrains.packagesearch.intellij.plugin.extensibility.Dependency
import com.jetbrains.packagesearch.intellij.plugin.extensibility.DependencyDeclarationCallback
import com.jetbrains.packagesearch.intellij.plugin.extensibility.DependencyDeclarationIndexes
import com.jetbrains.packagesearch.intellij.plugin.extensibility.ProjectModule
import com.jetbrains.packagesearch.intellij.plugin.util.logDebug
import kotlinx.coroutines.future.future
import org.jetbrains.plugins.gradle.model.ExternalProject
import org.jetbrains.plugins.gradle.service.project.data.ExternalProjectDataCache
import org.jetbrains.plugins.gradle.settings.GradleExtensionsSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

internal class GradleModuleTransformer : CoroutineModuleTransformer {

    companion object {

        private fun findDependencyElementIndex(file: PsiFile, dependency: Dependency): DependencyDeclarationIndexes? {
            val isKotlinDependencyInKts = file.language::class.qualifiedName == "org.jetbrains.kotlin.idea.KotlinLanguage"
                && dependency.groupId == "org.jetbrains.kotlin" && dependency.artifactId.startsWith("kotlin-")

            val textToSearchFor = buildString {
                appendEscapedToRegexp(dependency.scope)
                append("[\\(\\s]+")
                if (isKotlinDependencyInKts) {
                    append("(")
                    appendEscapedToRegexp("kotlin(\"")
                    appendEscapedToRegexp(dependency.artifactId.removePrefix("kotlin-"))
                    appendEscapedToRegexp("\")")
                } else {
                    append("[\\'\\\"]")
                    append("(")
                    appendEscapedToRegexp("${dependency.groupId}:${dependency.artifactId}:")
                    append("(\\\$?\\{?")
                    appendEscapedToRegexp(dependency.version)
                    append("\\}?)")
                }
                append(")")
                append("[\\'\\\"]")
                appendEscapedToRegexp(")")
                append("?")
            }

            val groups = Regex(textToSearchFor).find(file.text)?.groups ?: return null

            return groups[0]?.range?.first?.let {
                DependencyDeclarationIndexes(
                    wholeDeclarationStartIndex = it,
                    coordinatesStartIndex = groups[1]?.range?.first
                        ?: error("Cannot find coordinatesStartIndex for dependency $dependency in ${file.virtualFile.path}"),
                    versionStartIndex = groups[2]?.range?.first
                )
            }
        }
    }

    override suspend fun transformModules(project: Project, nativeModules: List<Module>): List<ProjectModule> {
        val nativeModulesByExternalProjectId = mutableMapOf<String, Module>()

        val rootProjects = nativeModules
            .filter { it.isNotGradleSourceSetModule() }
            .onEach { module ->
                val externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module)
                if (externalProjectId != null) nativeModulesByExternalProjectId[externalProjectId] = module
            }
            .mapNotNull { findRootExternalProjectOrNull(project, it) }
            .distinctBy { it.buildDir }

        val projectModulesByProjectDir = mutableMapOf<File, ProjectModule>()
        rootProjects.forEach { it.buildProjectModulesRecursively(projectModulesByProjectDir, nativeModulesByExternalProjectId, project) }

        return projectModulesByProjectDir.values.toList()
    }

    private suspend fun ExternalProject.buildProjectModulesRecursively(
        projectModulesByProjectDir: MutableMap<File, ProjectModule>,
        nativeModulesByExternalProjectId: Map<String, Module>,
        project: Project,
        parent: ProjectModule? = null
    ) {
        val nativeModule = checkNotNull(nativeModulesByExternalProjectId[id]) { "Couldn't find native module for '$id'" }

        val buildVirtualFile = buildFile?.absolutePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val projectVirtualDir = checkNotNull(LocalFileSystem.getInstance().findFileByPath(projectDir.absolutePath)) {
            "Couldn't find virtual file for build directory $projectDir in module $name"
        }

        val buildSystemType = when {
            buildVirtualFile == null -> BuildSystemType.GRADLE_CONTAINER
            isKotlinDsl(project, buildVirtualFile) -> BuildSystemType.GRADLE_KOTLIN
            else -> BuildSystemType.GRADLE_GROOVY
        }
        val scopes: List<String> = GradleExtensionsSettings.getInstance(project)
            .getExtensionsFor(nativeModule)?.configurations?.keys?.toList() ?: emptyList()

        val projectModule = ProjectModule(
            name = name,
            nativeModule = nativeModule,
            parent = parent,
            buildFile = buildVirtualFile,
            projectDir = projectVirtualDir,
            buildSystemType = buildSystemType,
            moduleType = GradleProjectModuleType,
            availableScopes = scopes,
            dependencyDeclarationCallback = getDependencyDeclarationCallback(project, buildVirtualFile)
        )

        for (childExternalProject in childProjects.values) {
            childExternalProject.buildProjectModulesRecursively(
                projectModulesByProjectDir,
                nativeModulesByExternalProjectId,
                project,
                parent = projectModule
            )
        }

        projectModulesByProjectDir[projectDir] = projectModule
    }

    private suspend fun isKotlinDsl(
        project: Project,
        buildVirtualFile: VirtualFile
    ) = readAction { runCatching { PsiManager.getInstance(project).findFile(buildVirtualFile) } }
        .getOrNull()
        ?.language
        ?.displayName
        ?.contains("kotlin", ignoreCase = true) == true

    private fun Module.isNotGradleSourceSetModule(): Boolean {
        if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, this)) return false
        return ExternalSystemApiUtil.getExternalModuleType(this) != GradleConstants.GRADLE_SOURCE_SET_MODULE_TYPE_KEY
    }

    private fun findRootExternalProjectOrNull(project: Project, module: Module): ExternalProject? {
        val rootProjectPath = ExternalSystemApiUtil.getExternalRootProjectPath(module)
        if (rootProjectPath == null) {
            logDebug(this::class.qualifiedName) {
                "Root external project was not yet imported, project=${project.projectFilePath}, module=${module.moduleFilePath}"
            }
            return null
        }

        val externalProjectDataCache = ExternalProjectDataCache.getInstance(project)
        return externalProjectDataCache.getRootExternalProject(rootProjectPath)
    }

    private fun getDependencyDeclarationCallback(
        project: Project,
        buildVirtualFile: VirtualFile?
    ): DependencyDeclarationCallback = { dependency ->
        project.lifecycleScope.future {
            readAction {
                buildVirtualFile ?: return@readAction null
                PsiManager.getInstance(project).findFile(buildVirtualFile)
                    ?.let { findDependencyElementIndex(it, dependency) }
            }
        }
    }
}
