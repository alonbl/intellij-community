// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.base.util.reformatted
import org.jetbrains.kotlin.idea.inspections.findExistingEditor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class MergeIfsIntention : SelfTargetingIntention<KtIfExpression>(KtIfExpression::class.java, KotlinBundle.lazyMessage("merge.if.s")) {
    override fun isApplicableTo(element: KtIfExpression, caretOffset: Int): Boolean {
        if (element.`else` != null) return false
        val then = element.then ?: return false

        val nestedIf = then.nestedIf() ?: return false
        if (nestedIf.`else` != null) return false

        return true
    }

    override fun applyTo(element: KtIfExpression, editor: Editor?) {
        applyTo(element)
    }

    companion object {
        fun applyTo(element: KtIfExpression): Int {
            val then = element.then
            val nestedIf = then?.nestedIf() ?: return -1
            val condition = element.condition ?: return -1
            val secondCondition = nestedIf.condition ?: return -1
            val nestedBody = nestedIf.then ?: return -1

            val factory = KtPsiFactory(element)

            val comments = element.allChildren.filter { it is PsiComment }.toList() + then.safeAs<KtBlockExpression>()
                ?.allChildren
                ?.filter { it is PsiComment }
                ?.toList()
                .orEmpty()

            if (comments.isNotEmpty()) {
                val parent = element.parent
                comments.forEach { comment ->
                    parent.addBefore(comment, element)
                    parent.addBefore(factory.createNewLine(), element)
                    comment.delete()
                }

                element.findExistingEditor()?.caretModel?.moveToOffset(element.startOffset)
            }

            condition.replace(factory.createExpressionByPattern("$0 && $1", condition, secondCondition))
            return then.replace(nestedBody).reformatted(true).textRange.startOffset
        }

        private fun KtExpression.nestedIf() = when (this) {
            is KtBlockExpression -> this.statements.singleOrNull() as? KtIfExpression
            is KtIfExpression -> this
            else -> null
        }
    }
}