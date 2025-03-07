// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic;

import com.intellij.codeWithMe.ClientId;
import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.impl.NotificationsManagerImpl;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.IconLikeCustomStatusBarWidget;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.impl.ProjectFrameHelper;
import com.intellij.ui.BalloonLayout;
import com.intellij.ui.BalloonLayoutData;
import com.intellij.ui.BalloonLayoutImpl;
import com.intellij.ui.ClickListener;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.TimeUnit;

public final class IdeMessagePanel extends NonOpaquePanel implements MessagePoolListener, IconLikeCustomStatusBarWidget {
  public static final String FATAL_ERROR = "FatalError";
  public static final boolean NO_DISTRACTION_MODE = Registry.is("ea.no.distraction.mode");

  private static final boolean NORMAL_MODE = !Boolean.getBoolean("fatal.error.icon.disable.blinking");

  private final IdeErrorsIcon myIcon;
  private final IdeFrame myFrame;
  private final MessagePool myMessagePool;

  private Balloon myBalloon;
  private IdeErrorsDialog myDialog;
  private boolean myOpeningInProgress;

  public IdeMessagePanel(@Nullable IdeFrame frame, @NotNull MessagePool messagePool) {
    super(new BorderLayout());

    myIcon = new IdeErrorsIcon(frame != null && NORMAL_MODE);
    myIcon.setVerticalAlignment(SwingConstants.CENTER);
    add(myIcon, BorderLayout.CENTER);
    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent event, int clickCount) {
        openErrorsDialog(null);
        return true;
      }
    }.installOn(myIcon);

    myFrame = frame;

    myMessagePool = messagePool;
    messagePool.addListener(this);

    updateIconAndNotify();
  }

  @Override
  public @NotNull String ID() {
    return FATAL_ERROR;
  }

  @Override
  public WidgetPresentation getPresentation() {
    return null;
  }

  @Override
  public void dispose() {
    UIUtil.dispose(myIcon);
    myMessagePool.removeListener(this);
  }

  @Override
  public void install(@NotNull StatusBar statusBar) { }

  @Override
  public JComponent getComponent() {
    return this;
  }

  public void openErrorsDialog(@Nullable LogMessage message) {
    if (myDialog != null) return;
    if (myOpeningInProgress) return;
    myOpeningInProgress = true;

    new Runnable() {
      @Override
      public void run() {
        if (!isOtherModalWindowActive()) {
          try (AccessToken ignored = ClientId.withClientId(ClientId.getLocalId())) {
            // always show IDE errors to the host
            doOpenErrorsDialog(message);
          }
          finally {
            myOpeningInProgress = false;
          }
        }
        else if (myDialog == null) {
          EdtExecutorService.getScheduledExecutorInstance().schedule(this, 300L, TimeUnit.MILLISECONDS);
        }
      }
    }.run();
  }

  private void doOpenErrorsDialog(@Nullable LogMessage message) {
    Project project = myFrame != null ? myFrame.getProject() : null;
    myDialog = new IdeErrorsDialog(myMessagePool, project, message) {
      @Override
      protected void dispose() {
        super.dispose();
        myDialog = null;
        updateIconAndNotify();
      }

      @Override
      protected void updateOnSubmit() {
        super.updateOnSubmit();
        updateIcon(myMessagePool.getState());
      }
    };
    myDialog.show();
  }

  private void updateIcon(MessagePool.State state) {
    UIUtil.invokeLaterIfNeeded(() -> {
      myIcon.setState(state);
      setVisible(state != MessagePool.State.NoErrors);
    });
  }

  @Override
  public void newEntryAdded() {
    updateIconAndNotify();
  }

  @Override
  public void poolCleared() {
    updateIconAndNotify();
  }

  @Override
  public void entryWasRead() {
    updateIconAndNotify();
  }

  private boolean isOtherModalWindowActive() {
    Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    return activeWindow instanceof JDialog &&
           ((JDialog)activeWindow).isModal() &&
           (myDialog == null || myDialog.getWindow() != activeWindow);
  }

  private void updateIconAndNotify() {
    MessagePool.State state = myMessagePool.getState();
    updateIcon(state);

    if (state == MessagePool.State.NoErrors) {
      if (myBalloon != null) {
        Disposer.dispose(myBalloon);
      }
    }
    else if (state == MessagePool.State.UnreadErrors && myBalloon == null && isActive(myFrame) && NORMAL_MODE && !NO_DISTRACTION_MODE) {
      Project project = myFrame.getProject();
      if (project != null) {
        ApplicationManager.getApplication().invokeLater(() -> showErrorNotification(project), project.getDisposed());
      }
    }
  }

  private static boolean isActive(@Nullable IdeFrame frame) {
    if (frame instanceof ProjectFrameHelper) {
      frame = ((ProjectFrameHelper)frame).getFrame();
    }
    return frame instanceof Window && ((Window)frame).isActive();
  }

  @RequiresEdt
  private void showErrorNotification(@NotNull Project project) {
    if (myBalloon != null) return;

    BalloonLayout layout = myFrame.getBalloonLayout();
    if (layout == null) {
      Logger.getInstance(IdeMessagePanel.class).error("frame=" + myFrame + " (" + myFrame.getClass() + ')');
      return;
    }

    //noinspection UnresolvedPluginConfigReference
    Notification notification = new Notification("", DiagnosticBundle.message("error.new.notification.title"), NotificationType.ERROR)
      .setIcon(AllIcons.Ide.FatalError)
      .addAction(NotificationAction.createSimpleExpiring(DiagnosticBundle.message("error.new.notification.link"), () -> openErrorsDialog(null)));

    BalloonLayoutData layoutData = BalloonLayoutData.createEmpty();
    layoutData.fadeoutTime = 10000;
    layoutData.textColor = JBUI.CurrentTheme.Notification.Error.FOREGROUND;
    layoutData.fillColor = JBUI.CurrentTheme.Notification.Error.BACKGROUND;
    layoutData.borderColor = JBUI.CurrentTheme.Notification.Error.BORDER_COLOR;
    layoutData.closeAll = () -> ((BalloonLayoutImpl)layout).closeAll();

    myBalloon = NotificationsManagerImpl.createBalloon(myFrame, notification, false, false, new Ref<>(layoutData), project);
    Disposer.register(myBalloon, () -> myBalloon = null);
    layout.add(myBalloon);
  }
}
