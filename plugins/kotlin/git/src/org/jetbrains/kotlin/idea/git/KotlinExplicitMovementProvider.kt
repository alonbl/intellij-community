// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Couple
import com.intellij.openapi.vcs.FilePath
import git4idea.checkin.GitCheckinExplicitMovementProvider
import org.jetbrains.kotlin.idea.base.codeInsight.pathBeforeJavaToKotlinConversion
import java.util.*

class KotlinExplicitMovementProvider : GitCheckinExplicitMovementProvider() {
    override fun isEnabled(project: Project): Boolean {
        return true
    }

    override fun getDescription(): String {
        return KotlinGitBundle.message("j2k.extra.commit.description")
    }

    override fun getCommitMessage(oldCommitMessage: String): String {
        return KotlinGitBundle.message("j2k.extra.commit.commit.message")
    }

    override fun collectExplicitMovements(
        project: Project,
        beforePaths: List<FilePath>,
        afterPaths: List<FilePath>
    ): Collection<Movement> {
        val movedChanges = ArrayList<Movement>()
        for (after in afterPaths) {
            val pathBeforeJ2K = after.virtualFile?.pathBeforeJavaToKotlinConversion
            if (pathBeforeJ2K != null) {
                val before = beforePaths.firstOrNull { it.path == pathBeforeJ2K }
                if (before != null) {
                    movedChanges.add(Movement(before, after))
                }
            }
        }

        return movedChanges
    }

    override fun afterMovementsCommitted(project: Project, movedPaths: MutableList<Couple<FilePath>>) {
        movedPaths.forEach { it.second.virtualFile?.pathBeforeJavaToKotlinConversion = null }
    }
}
