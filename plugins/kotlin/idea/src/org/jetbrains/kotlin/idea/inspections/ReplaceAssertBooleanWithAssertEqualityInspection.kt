// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

class ReplaceAssertBooleanWithAssertEqualityInspection : AbstractApplicabilityBasedInspection<KtCallExpression>(
    KtCallExpression::class.java
) {
    override fun inspectionText(element: KtCallExpression) = KotlinBundle.message("replace.assert.boolean.with.assert.equality")

    override val defaultFixText get() = KotlinBundle.message("replace.assert.boolean.with.assert.equality")

    override fun fixText(element: KtCallExpression): String {
        val assertion = element.replaceableAssertion() ?: return defaultFixText
        return KotlinBundle.message("replace.with.0", assertion)
    }

    override fun isApplicable(element: KtCallExpression): Boolean {
        return (element.replaceableAssertion() != null)
    }

    override fun applyTo(element: KtCallExpression, project: Project, editor: Editor?) {
        val valueArguments = element.valueArguments
        val condition = valueArguments.firstOrNull()?.getArgumentExpression() as? KtBinaryExpression ?: return
        val left = condition.left ?: return
        val right = condition.right ?: return
        val assertion = element.replaceableAssertion() ?: return

        val file = element.containingKtFile
        val factory = KtPsiFactory(project)
        val replaced = if (valueArguments.size == 2) {
            val message = valueArguments[1].getArgumentExpression() ?: return
            element.replaced(factory.createExpressionByPattern("$assertion($0, $1, $2)", left, right, message))
        } else {
            element.replaced(factory.createExpressionByPattern("$assertion($0, $1)", left, right))
        }
        ShortenReferences.DEFAULT.process(replaced)
        OptimizeImportsProcessor(project, file).run()
    }

    private fun KtCallExpression.replaceableAssertion(): String? {
        val referencedName = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (referencedName !in Holder.assertions) {
            return null
        }

        val context = safeAnalyzeNonSourceRootCode(BodyResolveMode.PARTIAL)
        if (descriptor(context)?.containingDeclaration?.fqNameSafe != FqName(Holder.kotlinTestPackage)) {
            return null
        }

        if (valueArguments.size != 1 && valueArguments.size != 2) return null
        val binaryExpression = valueArguments.first().getArgumentExpression() as? KtBinaryExpression ?: return null
        val leftType = binaryExpression.left?.type(context) ?: return null
        val rightType = binaryExpression.right?.type(context) ?: return null
        if (!leftType.isSubtypeOf(rightType) && !rightType.isSubtypeOf(leftType)) return null
        val operationToken = binaryExpression.operationToken

        return Holder.assertionMap[Pair(referencedName, operationToken)]
    }

    private fun KtExpression.descriptor(context: BindingContext): CallableDescriptor? {
        return getResolvedCall(context)?.resultingDescriptor
    }

    private fun KtExpression.type(context: BindingContext): KotlinType? {
        return descriptor(context)?.returnType
    }

    private object Holder {
        const val kotlinTestPackage: String = "kotlin.test"
        val assertions: Set<String> = setOf("assertTrue", "assertFalse")

        val assertionMap: Map<Pair<String, KtSingleValueToken>, String> = mapOf(
            Pair("assertTrue", KtTokens.EQEQ) to "$kotlinTestPackage.assertEquals",
            Pair("assertTrue", KtTokens.EQEQEQ) to "$kotlinTestPackage.assertSame",
            Pair("assertFalse", KtTokens.EQEQ) to "$kotlinTestPackage.assertNotEquals",
            Pair("assertFalse", KtTokens.EQEQEQ) to "$kotlinTestPackage.assertNotSame"
        )
    }
}