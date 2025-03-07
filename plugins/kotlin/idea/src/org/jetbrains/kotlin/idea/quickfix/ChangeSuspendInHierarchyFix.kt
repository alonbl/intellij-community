// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptorWithResolutionScopes
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.util.runSynchronouslyWithProgress
import org.jetbrains.kotlin.idea.search.declarationsSearch.HierarchySearchRequest
import org.jetbrains.kotlin.idea.search.declarationsSearch.searchInheritors
import org.jetbrains.kotlin.idea.base.util.useScope
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.util.getTypeSubstitution
import org.jetbrains.kotlin.idea.util.substitute
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.isSubclassOf
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.util.findCallableMemberBySignature
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.ifEmpty

class ChangeSuspendInHierarchyFix(
    element: KtNamedFunction,
    private val addModifier: Boolean
) : KotlinQuickFixAction<KtNamedFunction>(element) {
    override fun getFamilyName(): String {
        return if (addModifier) {
            KotlinBundle.message("fix.change.suspend.hierarchy.add")
        } else {
            KotlinBundle.message("fix.change.suspend.hierarchy.remove")
        }
    }

    override fun getText() = familyName

    override fun startInWriteAction() = false

    private fun findAllFunctionToProcess(project: Project): Set<KtNamedFunction> {
        val result = LinkedHashSet<KtNamedFunction>()

        val progressIndicator = ProgressManager.getInstance().progressIndicator!!

        val function = element ?: return emptySet()
        val functionDescriptor = function.unsafeResolveToDescriptor() as FunctionDescriptor

        val baseFunctionDescriptors = functionDescriptor.findTopMostOverriddables()
        baseFunctionDescriptors.forEach { baseFunctionDescriptor ->
            val baseClassDescriptor = baseFunctionDescriptor.containingDeclaration as? ClassDescriptor ?: return@forEach
            val baseClass = DescriptorToSourceUtilsIde.getAnyDeclaration(project, baseClassDescriptor) ?: return@forEach

            val name = (baseClass as? PsiNamedElement)?.name ?: return@forEach
            progressIndicator.text = KotlinBundle.message("fix.change.progress.looking.inheritors", name)
            val classes = listOf(baseClass) + HierarchySearchRequest(baseClass, baseClass.useScope()).searchInheritors()
            classes.mapNotNullTo(result) {
                val subClass = it.unwrapped as? KtClassOrObject ?: return@mapNotNullTo null
                val classDescriptor = subClass.unsafeResolveToDescriptor() as ClassDescriptor
                val substitution = getTypeSubstitution(baseClassDescriptor.defaultType, classDescriptor.defaultType)
                    ?: return@mapNotNullTo null
                val signatureInSubClass = baseFunctionDescriptor.substitute(substitution) as FunctionDescriptor
                val subFunctionDescriptor = classDescriptor.findCallableMemberBySignature(signatureInSubClass, true)
                    ?: return@mapNotNullTo null
                subFunctionDescriptor.source.getPsi() as? KtNamedFunction
            }
        }

        return result
    }

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val functions = project.runSynchronouslyWithProgress(KotlinBundle.message("fix.change.progress.analyzing.class.hierarchy"), true) {
            runReadAction { findAllFunctionToProcess(project) }
        } ?: return

        runWriteAction {
            functions.forEach {
                if (addModifier) {
                    it.addModifier(KtTokens.SUSPEND_KEYWORD)
                } else {
                    it.removeModifier(KtTokens.SUSPEND_KEYWORD)
                }
            }
        }
    }

    companion object : KotlinIntentionActionsFactory() {
        fun FunctionDescriptor.findTopMostOverriddables(): List<FunctionDescriptor> {
            val overridablesCache = HashMap<FunctionDescriptor, List<FunctionDescriptor>>()

            fun FunctionDescriptor.getOverridables(): List<FunctionDescriptor> {
                return overridablesCache.getOrPut(this) {
                    val classDescriptor = containingDeclaration as? ClassDescriptorWithResolutionScopes ?: return emptyList()
                    DescriptorUtils.getSuperclassDescriptors(classDescriptor).flatMap { superClassDescriptor ->
                        if (superClassDescriptor !is ClassDescriptorWithResolutionScopes) return@flatMap emptyList<FunctionDescriptor>()
                        val candidates =
                            superClassDescriptor.unsubstitutedMemberScope.getContributedFunctions(name, NoLookupLocation.FROM_IDE)
                        val substitution = getTypeSubstitution(superClassDescriptor.defaultType, classDescriptor.defaultType)
                            ?: return@flatMap emptyList<FunctionDescriptor>()
                        candidates.filter {
                            val signature = it.substitute(substitution) as FunctionDescriptor
                            classDescriptor.findCallableMemberBySignature(signature, true) == this
                        }
                    }
                }
            }

            return DFS.dfs(
                listOf(this),
                { it?.getOverridables() ?: emptyList() },
                object : DFS.CollectingNodeHandler<FunctionDescriptor, FunctionDescriptor, ArrayList<FunctionDescriptor>>(ArrayList()) {
                    override fun afterChildren(current: FunctionDescriptor) {
                        if (current.getOverridables().isEmpty()) {
                            result.add(current)
                        }
                    }
                })
        }

        private fun Collection<DeclarationDescriptor>.getOverridables(
            currentDescriptor: FunctionDescriptor
        ): List<DeclarationDescriptor> {
            val currentClassDescriptor = currentDescriptor.containingDeclaration as? ClassDescriptor ?: return emptyList()
            return filter {
                if (it !is FunctionDescriptor || it == currentDescriptor) return@filter false
                if (it.isSuspend == currentDescriptor.isSuspend) return@filter false
                val containingClassDescriptor = it.containingDeclaration as? ClassDescriptor ?: return@filter false
                if (!currentClassDescriptor.isSubclassOf(containingClassDescriptor)) return@filter false
                val substitution = getTypeSubstitution(containingClassDescriptor.defaultType, currentClassDescriptor.defaultType)
                    ?: return@filter false
                val signatureInCurrentClass = it.substitute(substitution) ?: return@filter false
                OverridingUtil.DEFAULT.isOverridableBy(signatureInCurrentClass, currentDescriptor, null).result ==
                        OverridingUtil.OverrideCompatibilityInfo.Result.CONFLICT
            }
        }

        override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction> {
            val currentFunction = diagnostic.psiElement as? KtNamedFunction ?: return emptyList()
            val currentDescriptor = currentFunction.unsafeResolveToDescriptor() as FunctionDescriptor
            Errors.CONFLICTING_OVERLOADS.cast(diagnostic).a.getOverridables(currentDescriptor).ifEmpty { return emptyList() }

            return listOf(
                ChangeSuspendInHierarchyFix(currentFunction, true),
                ChangeSuspendInHierarchyFix(currentFunction, false)
            )
        }
    }
}