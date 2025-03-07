// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.welcomeScreen.cloneableProjects

import com.intellij.CommonBundle
import com.intellij.ide.RecentProjectMetaInfo
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.TaskInfo
import com.intellij.openapi.progress.util.AbstractProgressIndicatorExBase
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.ex.ProgressIndicatorEx
import com.intellij.openapi.wm.impl.welcomeScreen.recentProjects.CloneableProjectItem
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.SystemIndependent
import java.util.*

@Service(Level.APP)
class CloneableProjectsService {
  private val cloneableProjects: MutableSet<CloneableProject> = Collections.synchronizedSet(mutableSetOf())

  @RequiresEdt
  fun runCloneTask(projectPath: @SystemIndependent String, cloneTask: CloneTask) {
    val taskInfo = cloneTask.taskInfo()
    val progressIndicator = CloneableProjectProgressIndicator(taskInfo)
    val cloneableProject = CloneableProject(projectPath, taskInfo, progressIndicator, CloneStatus.PROGRESS)
    addCloneableProject(cloneableProject)

    ApplicationManager.getApplication().executeOnPooledThread {
      ProgressManager.getInstance().runProcess(Runnable {
        val activity = VcsCloneCollector.cloneStarted()
        val cloneStatus: CloneStatus = try {
          cloneTask.run(progressIndicator)
        }
        catch (_: ProcessCanceledException) {
          CloneStatus.CANCEL
        }
        catch (exception: Throwable) {
          logger<CloneableProjectsService>().error(exception)
          CloneStatus.FAILURE
        }
        VcsCloneCollector.cloneFinished(activity, cloneStatus)

        when (cloneStatus) {
          CloneStatus.SUCCESS -> onSuccess(cloneableProject)
          CloneStatus.FAILURE -> onFailure(cloneableProject)
          CloneStatus.CANCEL -> onCancel(cloneableProject)
          else -> {}
        }
      }, progressIndicator)
    }
  }

  fun collectCloneableProjects(): List<CloneableProjectItem> {
    val recentProjectManager = RecentProjectsManager.getInstance() as RecentProjectsManagerBase

    return cloneableProjects.map { cloneableProject ->
      val projectPath = cloneableProject.projectPath
      val projectName = recentProjectManager.getProjectName(projectPath)
      val displayName = recentProjectManager.getDisplayName(projectPath) ?: projectName

      CloneableProjectItem(projectPath, projectName, displayName, cloneableProject)
    }
  }

  fun cloneCount(): Int {
    return cloneableProjects.size
  }

  fun isCloneActive(): Boolean {
    return !cloneableProjects.isEmpty()
  }

  fun cancelClone(cloneableProject: CloneableProject) {
    cloneableProject.progressIndicator.cancel()
  }

  fun removeCloneableProject(cloneableProject: CloneableProject) {
    if (cloneableProject.cloneStatus == CloneStatus.PROGRESS) {
      cloneableProject.progressIndicator.cancel()
    }

    cloneableProjects.removeIf { it.projectPath == cloneableProject.projectPath }
    fireCloneRemovedEvent()
  }

  private fun upgradeCloneProjectToRecent(cloneableProject: CloneableProject) {
    val recentProjectsManager = RecentProjectsManager.getInstance() as RecentProjectsManagerBase
    recentProjectsManager.addRecentPath(cloneableProject.projectPath, RecentProjectMetaInfo())
    removeCloneableProject(cloneableProject)
  }

  private fun addCloneableProject(cloneableProject: CloneableProject) {
    cloneableProjects.removeIf { it.projectPath == cloneableProject.projectPath }
    cloneableProjects.add(cloneableProject)
    fireCloneAddedEvent(cloneableProject)
  }

  private fun onSuccess(cloneableProject: CloneableProject) {
    cloneableProject.cloneStatus = CloneStatus.SUCCESS
    upgradeCloneProjectToRecent(cloneableProject)
    fireCloneSuccessEvent()
  }

  private fun onFailure(cloneableProject: CloneableProject) {
    cloneableProject.cloneStatus = CloneStatus.FAILURE
    fireCloneFailedEvent()
  }

  private fun onCancel(cloneableProject: CloneableProject) {
    cloneableProject.cloneStatus = CloneStatus.CANCEL
    fireCloneCanceledEvent()
  }

  private fun fireCloneAddedEvent(cloneableProject: CloneableProject) {
    ApplicationManager.getApplication().messageBus
      .syncPublisher(TOPIC)
      .onCloneAdded(cloneableProject.progressIndicator, cloneableProject.cloneTaskInfo)
  }

  private fun fireCloneRemovedEvent() {
    ApplicationManager.getApplication().messageBus
      .syncPublisher(TOPIC)
      .onCloneRemoved()
  }

  private fun fireCloneSuccessEvent() {
    ApplicationManager.getApplication().messageBus
      .syncPublisher(TOPIC)
      .onCloneSuccess()
  }

  private fun fireCloneFailedEvent() {
    ApplicationManager.getApplication().messageBus
      .syncPublisher(TOPIC)
      .onCloneFailed()
  }

  private fun fireCloneCanceledEvent() {
    ApplicationManager.getApplication().messageBus
      .syncPublisher(TOPIC)
      .onCloneCanceled()
  }

  enum class CloneStatus {
    SUCCESS,
    PROGRESS,
    FAILURE,
    CANCEL
  }

  class CloneTaskInfo(
    @NlsContexts.ProgressTitle private val title: String,
    @Nls private val cancelTooltipText: String,
    @Nls val actionTitle: String,
    @Nls val actionTooltipText: String,
    @Nls val failedTitle: String,
    @Nls val canceledTitle: String,
    @Nls val stopTitle: String,
    @Nls val stopDescription: String
  ) : TaskInfo {
    override fun getTitle(): String = title
    override fun getCancelText(): String = CommonBundle.getCancelButtonText()
    override fun getCancelTooltipText(): String = cancelTooltipText
    override fun isCancellable(): Boolean = true
  }

  data class CloneableProject(
    val projectPath: @SystemIndependent String,
    val cloneTaskInfo: CloneTaskInfo,
    val progressIndicator: ProgressIndicatorEx,
    var cloneStatus: CloneStatus
  )

  private class CloneableProjectProgressIndicator(cloneTaskInfo: CloneTaskInfo) : AbstractProgressIndicatorExBase() {
    init {
      setOwnerTask(cloneTaskInfo)
    }
  }

  interface CloneTask {
    fun taskInfo(): CloneTaskInfo

    fun run(indicator: ProgressIndicator): CloneStatus
  }

  interface CloneProjectListener {
    @JvmDefault
    fun onCloneAdded(progressIndicator: ProgressIndicatorEx, taskInfo: TaskInfo) {
    }

    @JvmDefault
    fun onCloneRemoved() {
    }

    @JvmDefault
    fun onCloneSuccess() {
    }

    @JvmDefault
    fun onCloneFailed() {
    }

    @JvmDefault
    fun onCloneCanceled() {
    }
  }

  companion object {
    @JvmField
    val TOPIC = Topic(CloneProjectListener::class.java)

    @JvmStatic
    fun getInstance() = service<CloneableProjectsService>()
  }
}