// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl;

import com.intellij.accessibility.AccessibilityUtils;
import com.intellij.ide.actions.CustomizeUIAction;
import com.intellij.ide.actions.ViewToolbarAction;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.ide.ui.customization.CustomActionsSchema;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.IdeRootPaneNorthExtension;
import com.intellij.openapi.wm.StatusBarCentralWidget;
import com.intellij.openapi.wm.WindowInfoKt;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.MacToolbarFrameHeader;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.MainFrameCustomHeader;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.MenuFrameHeader;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.titleLabel.CustomDecorationPath;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.toolbar.ToolbarFrameHeader;
import com.intellij.openapi.wm.impl.headertoolbar.MainToolbar;
import com.intellij.openapi.wm.impl.headertoolbar.MainToolbarKt;
import com.intellij.openapi.wm.impl.status.IdeStatusBarImpl;
import com.intellij.toolWindow.ToolWindowButtonManager;
import com.intellij.toolWindow.ToolWindowPane;
import com.intellij.toolWindow.ToolWindowPaneNewButtonManager;
import com.intellij.toolWindow.ToolWindowPaneOldButtonManager;
import com.intellij.ui.*;
import com.intellij.ui.components.JBBox;
import com.intellij.ui.components.JBLayeredPane;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StartupUiUtil;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.JBR;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
@ApiStatus.Internal
public class IdeRootPane extends JRootPane implements UISettingsListener {
  /**
   * Toolbar and status bar.
   */
  private JComponent myToolbar;
  private IdeStatusBarImpl myStatusBar;
  private boolean myStatusBarDisposed;

  private final JBBox myNorthPanel = JBBox.createVerticalBox();
  private final List<IdeRootPaneNorthExtension> myNorthComponents = new ArrayList<>();
  private IdeRootPaneNorthExtension myStatusBarCentralWidget;

  private ToolWindowPane toolWindowPane;
  private JBPanel<?> myContentPane;

  private final boolean myGlassPaneInitialized;

  private boolean myFullScreen;

  private MainFrameCustomHeader myCustomFrameTitlePane;
  private CustomDecorationPath mySelectedEditorFilePath;

  protected IdeRootPane(@NotNull JFrame frame, @NotNull IdeFrame frameHelper, @NotNull Disposable parentDisposable) {
    if (SystemInfoRt.isWindows && (StartupUiUtil.isUnderDarcula() || UIUtil.isUnderIntelliJLaF())) {
      try {
        setWindowDecorationStyle(FRAME);
      }
      catch (Exception e) {
        Logger.getInstance(IdeRootPane.class).error(e);
      }
    }

    myContentPane.add(IdeFrameDecorator.wrapRootPaneNorthSide(this, myNorthPanel), BorderLayout.NORTH);

    // listen to mouse motion events for a11y
    myContentPane.addMouseMotionListener(new MouseMotionAdapter() {
    });

    IdeMenuBar menu = IdeMenuBar.createMenuBar().setFrame(frame);
    if (!isDecoratedMenu() && !FrameInfoHelper.isFloatingMenuBarSupported()) {
      setJMenuBar(menu);
    }
    else {
      if (isDecoratedMenu()) {
        JBR.getCustomWindowDecoration().setCustomDecorationEnabled(frame, true);
        ToolbarUtil.removeSystemTitleBar(this);

        mySelectedEditorFilePath = CustomDecorationPath.Companion.createMainInstance(frame);
        IdeMenuBar ideMenu = IdeMenuBar.createMenuBar().setFrame(frame);
        myCustomFrameTitlePane = createCustomTitle(frame, ideMenu);
        getLayeredPane().add(myCustomFrameTitlePane.getComponent(), Integer.valueOf(JLayeredPane.DEFAULT_LAYER - 2));
      }

      if (FrameInfoHelper.isFloatingMenuBarSupported() && !isMenuButtonInToolbar()) {
        menuBar = menu;
        getLayeredPane().add(menuBar, Integer.valueOf(JLayeredPane.DEFAULT_LAYER - 1));
      }

      addPropertyChangeListener(IdeFrameDecorator.FULL_SCREEN, __ -> updateScreenState(frameHelper));
      updateScreenState(frameHelper);
    }

    IdeGlassPaneImpl glassPane = new IdeGlassPaneImpl(this, true);
    setGlassPane(glassPane);
    myGlassPaneInitialized = true;
    if (frame instanceof IdeFrameImpl) {
      putClientProperty(UIUtil.NO_BORDER_UNDER_WINDOW_TITLE_KEY, Boolean.TRUE);
    }
    UIUtil.decorateWindowHeader(this);
    glassPane.setVisible(false);
    setBorder(UIManager.getBorder("Window.border"));

    if (!isDecoratedMenu()) {
      ToolbarUtil.setCustomTitleBar(frame, this, runnable -> {
        Disposer.register(parentDisposable, runnable::run);
      });
    }

    updateMainMenuVisibility();

    myContentPane.add(createCenterComponent(frame, parentDisposable), BorderLayout.CENTER);
  }

  @NotNull
  private MainFrameCustomHeader createCustomTitle(@NotNull JFrame frame, IdeMenuBar ideMenu) {
    if (ExperimentalUI.isNewUI()) {
      return SystemInfo.isMac ? new MacToolbarFrameHeader(frame, this, ideMenu) : new ToolbarFrameHeader(frame, ideMenu);
    }

    return new MenuFrameHeader(frame, mySelectedEditorFilePath, ideMenu);
  }

  /**
   * @return not-null action group or null to use {@link IdeActions#GROUP_MAIN_MENU} action group
   */
  protected @Nullable ActionGroup getMainMenuActionGroup() {
    return null;
  }

  protected @NotNull Component createCenterComponent(@NotNull JFrame frame, @NotNull Disposable parentDisposable) {
    String paneId = WindowInfoKt.WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID;

    final ToolWindowButtonManager toolWindowButtonManager;
    if (ExperimentalUI.isNewUI()) {
      toolWindowButtonManager = new ToolWindowPaneNewButtonManager(paneId);
      toolWindowButtonManager.add(myContentPane);
    }
    else {
      toolWindowButtonManager = new ToolWindowPaneOldButtonManager(paneId);
    }

    toolWindowPane = new ToolWindowPane(frame, parentDisposable, paneId, toolWindowButtonManager);
    return toolWindowPane;
  }

  public @NotNull ToolWindowPane getToolWindowPane() {
    return toolWindowPane;
  }

  private void updateScreenState(@NotNull IdeFrame helper) {
    myFullScreen = helper.isInFullScreen();

    if (isDecoratedMenu()) {
      JMenuBar bar = getJMenuBar();
      if (bar != null) {
        bar.setVisible(myFullScreen);
      }

      if (myCustomFrameTitlePane != null) {
        myCustomFrameTitlePane.getComponent().setVisible(!myFullScreen);
      }
    }
  }

  @Override
  protected LayoutManager createRootLayout() {
    return FrameInfoHelper.isFloatingMenuBarSupported() || isDecoratedMenu()
           ? new MyRootLayout() : super.createRootLayout();
  }

  @Override
  public void setGlassPane(final Component glass) {
    if (myGlassPaneInitialized) throw new IllegalStateException("Setting of glass pane for IdeFrame is prohibited");
    super.setGlassPane(glass);
  }

  /**
   * Invoked when enclosed frame is being shown.
   */
  @Override
  public final void addNotify() {
    super.addNotify();
  }

  /**
   * Invoked when enclosed frame is being disposed.
   */
  @Override
  public final void removeNotify() {
    if (ScreenUtil.isStandardAddRemoveNotify(this)) {
      if (!myStatusBarDisposed) {
        myStatusBarDisposed = true;
        Disposer.dispose(myStatusBar);
      }
      setJMenuBar(null);
      if (myCustomFrameTitlePane != null) {
        layeredPane.remove(myCustomFrameTitlePane.getComponent());
        Disposer.dispose(myCustomFrameTitlePane);
      }
    }
    super.removeNotify();
  }

  @Override
  protected final JLayeredPane createLayeredPane() {
    JLayeredPane p = new JBLayeredPane();
    p.setName(getName() + ".layeredPane");
    return p;
  }

  @Override
  protected final Container createContentPane() {
    myContentPane = new JBPanel<>(new BorderLayout());
    myContentPane.setBackground(IdeBackgroundUtil.getIdeBackgroundColor());
    return myContentPane;
  }

  public final void prepareToolbar() {
    if (getToolbarHolderDelegate() == null && ExperimentalUI.isNewUI()) {
      MainToolbar toolbar = new MainToolbar();
      toolbar.setBorder(JBUI.Borders.empty(0, 10));

      myToolbar = toolbar;
      myNorthPanel.add(myToolbar, 0);
      updateToolbarVisibility();
      myContentPane.revalidate();
    }
  }

  public final void initOrCreateToolbar(@NotNull Project project) {
    if (getToolbarHolderDelegate() == null && ExperimentalUI.isNewUI()) {
      JComponent toolbar = myToolbar;
      // null if frame is reused (open project in an existing frame)
      if (toolbar != null) {
        ((MainToolbar)toolbar).init(project);
        return;
      }
    }

    updateToolbar();
  }

  final void updateToolbar() {
    ToolbarHolder delegate = getToolbarHolderDelegate();
    if (delegate == null) {
      doUpdateToolbarWithoutDelegate();
    }
    else {
      delegate.updateToolbar();
    }
  }

  private void doUpdateToolbarWithoutDelegate() {
    removeToolbar();
    myToolbar = createToolbar();
    myNorthPanel.add(myToolbar, 0);
    updateToolbarVisibility();
    myContentPane.revalidate();
  }

  final void removeToolbar() {
    ToolbarHolder delegate = getToolbarHolderDelegate();
    if (delegate != null) {
      delegate.removeToolbar();
      return;
    }

    if (myToolbar != null) {
      disposeIfNeeded(myToolbar);
      myNorthPanel.remove(myToolbar);
      myToolbar = null;
    }
  }

  private @Nullable ToolbarHolder getToolbarHolderDelegate() {
    if (MainToolbarKt.isToolbarInHeader() && ExperimentalUI.isNewUI()) {
      return (ToolbarHolder)myCustomFrameTitlePane;
    }
    return null;
  }

  private static void disposeIfNeeded(@NotNull JComponent component) {
    if (!(component instanceof Disposable)) {
      return;
    }

    Disposable d = (Disposable)component;
    if (!Disposer.isDisposed(d)) {
      Disposer.dispose(d);
    }
  }


  public void updateNorthComponents() {
    for (IdeRootPaneNorthExtension northComponent : myNorthComponents) {
      northComponent.revalidate();
    }
    myContentPane.revalidate();
  }

  void updateMainMenuActions() {
    if (menuBar != null) {
      ((IdeMenuBar)menuBar).updateMenuActions(false);
      menuBar.repaint();
    }

    if (myCustomFrameTitlePane != null) {
      myCustomFrameTitlePane.updateMenuActions(false);
      myCustomFrameTitlePane.getComponent().repaint();
    }
  }

  private @NotNull JComponent createToolbar() {
    if (ExperimentalUI.isNewUI()) {
      IdeFrame frame = ComponentUtil.getParentOfType(IdeFrameImpl.class, this);
      MainToolbar toolbar = new MainToolbar();
      toolbar.init(frame == null ? null : frame.getProject());
      toolbar.setBorder(JBUI.Borders.empty(0, 10));
      return toolbar;
    }

    ActionGroup group = (ActionGroup)CustomActionsSchema.getInstance().getCorrectedAction(IdeActions.GROUP_MAIN_TOOLBAR);
    ActionToolbar toolBar = ActionManagerEx.getInstanceEx()
      .createActionToolbar(ActionPlaces.MAIN_TOOLBAR, Objects.requireNonNull(group), true);
    toolBar.setTargetComponent(null);
    toolBar.setLayoutPolicy(ActionToolbar.WRAP_LAYOUT_POLICY);

    PopupHandler.installPopupMenu(toolBar.getComponent(), "MainToolbarPopupActions", "MainToolbarPopup");

    return toolBar.getComponent();
  }

  public void createAndConfigureStatusBar(@NotNull IdeFrame frame, @NotNull Disposable parentDisposable) {
    myStatusBar = createStatusBar(frame);
    Disposer.register(parentDisposable, myStatusBar);

    updateStatusBarVisibility();
    myContentPane.add(myStatusBar, BorderLayout.SOUTH);
  }

  protected @NotNull IdeStatusBarImpl createStatusBar(@NotNull IdeFrame frame) {
    boolean addToolWindowsWidget = !ExperimentalUI.isNewUI()
                                   && !AccessibilityUtils.isScreenReaderDetected();
    return new IdeStatusBarImpl(frame, addToolWindowsWidget);
  }

  final @Nullable IdeStatusBarImpl getStatusBar() {
    return myStatusBar;
  }

  public int getStatusBarHeight() {
    IdeStatusBarImpl statusBar = myStatusBar;
    return (statusBar != null && statusBar.isVisible()) ? statusBar.getHeight() : 0;
  }

  private void updateToolbarVisibility() {
    if (myToolbar == null) {
      myToolbar = createToolbar();
      myNorthPanel.add(myToolbar, 0);
    }

    UISettings uiSettings = UISettings.getShadowInstance();
    boolean isNewToolbar = ExperimentalUI.isNewUI();
    boolean visible = ((isNewToolbar && !MainToolbarKt.isToolbarInHeader(uiSettings))
                       || (!isNewToolbar && uiSettings.getShowMainToolbar()))
                      && !uiSettings.getPresentationMode();
    myToolbar.setVisible(visible);
  }

  private void updateStatusBarVisibility() {
    UISettings uiSettings = UISettings.getShadowInstance();
    myStatusBar.setVisible(uiSettings.getShowStatusBar() && !uiSettings.getPresentationMode());
  }

  private void updateMainMenuVisibility() {
    UISettings uiSettings = UISettings.getShadowInstance();
    if (uiSettings.getPresentationMode() || IdeFrameDecorator.isCustomDecorationActive()) {
      return;
    }

    boolean globalMenuVisible = SystemInfoRt.isLinux && GlobalMenuLinux.isPresented();
    // don't show swing-menu when global (system) menu presented
    boolean visible = SystemInfo.isMacSystemMenu || (!globalMenuVisible && uiSettings.getShowMainMenu());
    if (menuBar != null && visible != menuBar.isVisible()) {
      menuBar.setVisible(visible);
    }
  }

  void setProject(Project project) {
    if (mySelectedEditorFilePath != null) {
      mySelectedEditorFilePath.setProject(project);
    }
  }

  protected void installNorthComponents(@NotNull Project project) {
    UISettings uiSettings = UISettings.getShadowInstance();
    myStatusBarCentralWidget = IdeRootPaneNorthExtension.EP_NAME.findFirstSafe(project, it -> it instanceof StatusBarCentralWidget);

    myNorthComponents.addAll(IdeRootPaneNorthExtension.EP_NAME.getExtensions(project));
    if (myNorthComponents.isEmpty()) {
      return;
    }

    for (IdeRootPaneNorthExtension northComponent : myNorthComponents) {
      myNorthPanel.add(northComponent.getComponent());
      northComponent.uiSettingsChanged(uiSettings);
    }
  }

  protected void deinstallNorthComponents() {
    int count = myNorthPanel.getComponentCount();
    for (int i = count - 1; i >= 0; i--) {
      if (myNorthPanel.getComponent(i) != myToolbar) {
        myNorthPanel.remove(i);
      }
    }

    for (IdeRootPaneNorthExtension northComponent : myNorthComponents) {
      if (northComponent instanceof Disposable && !Disposer.isDisposed((Disposable)northComponent)) {
        Disposer.dispose((Disposable)northComponent);
      }
    }
    myNorthComponents.clear();
  }

  public @Nullable IdeRootPaneNorthExtension findByName(@NotNull String name) {
    return ExperimentalUI.isNewUI() && IdeStatusBarImpl.NAVBAR_WIDGET_KEY.equals(name)
           ? myStatusBarCentralWidget
           : ContainerUtil.find(myNorthComponents, c -> c.getKey().equals(name));
  }

  @Override
  public void uiSettingsChanged(@NotNull UISettings uiSettings) {
    UIUtil.decorateWindowHeader(this);
    updateToolbarVisibility();
    updateStatusBarVisibility();
    updateMainMenuVisibility();

    if (!ExperimentalUI.isNewUI()) {
      for (IdeRootPaneNorthExtension component : myNorthComponents) {
        component.uiSettingsChanged(uiSettings);
      }
    }

    IdeFrameImpl frame = ComponentUtil.getParentOfType(IdeFrameImpl.class, this);
    if (frame == null) {
      return;
    }

    frame.setBackground(UIUtil.getPanelBackground());

    BalloonLayout layout = frame.getBalloonLayout();
    if (layout instanceof BalloonLayoutImpl) {
      ((BalloonLayoutImpl)layout).queueRelayout();
    }
  }

  private final class MyRootLayout extends RootLayout {
    @Override
    public Dimension preferredLayoutSize(Container parent) {
      Dimension rd;
      Insets i = getInsets();

      if (contentPane != null) {
        rd = contentPane.getPreferredSize();
      }
      else {
        rd = parent.getSize();
      }

      Dimension dimension;
      if (myCustomFrameTitlePane != null && myCustomFrameTitlePane.getComponent().isVisible()) {
        dimension = myCustomFrameTitlePane.getComponent().getPreferredSize();
      }
      else {
        dimension = JBUI.emptySize();
      }

      Dimension mbd;
      if (menuBar != null && menuBar.isVisible() && !myFullScreen && !isDecoratedMenu()) {
        mbd = menuBar.getPreferredSize();
      }
      else {
        mbd = JBUI.emptySize();
      }
      return new Dimension(Math.max(rd.width, mbd.width) + i.left + i.right + dimension.width,
                           rd.height + mbd.height + i.top + i.bottom + dimension.height);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      Dimension rd;
      Insets i = getInsets();
      if (contentPane != null) {
        rd = contentPane.getMinimumSize();
      }
      else {
        rd = parent.getSize();
      }

      Dimension dimension;
      if (isDecoratedMenu() && myCustomFrameTitlePane != null && myCustomFrameTitlePane.getComponent().isVisible()) {
        dimension = myCustomFrameTitlePane.getComponent().getPreferredSize();
      }
      else {
        dimension = JBUI.emptySize();
      }

      Dimension mbd;
      if (menuBar != null && menuBar.isVisible() && !myFullScreen && !isDecoratedMenu()) {
        mbd = menuBar.getMinimumSize();
      }
      else {
        mbd = JBUI.emptySize();
      }
      return new Dimension(Math.max(rd.width, mbd.width) + i.left + i.right + dimension.width,
                           rd.height + mbd.height + i.top + i.bottom + dimension.height);
    }

    @Override
    public Dimension maximumLayoutSize(Container target) {
      Dimension mbd;
      Insets i = getInsets();
      if (menuBar != null && menuBar.isVisible() && !myFullScreen && !isDecoratedMenu()) {
        mbd = menuBar.getMaximumSize();
      }
      else {
        mbd = JBUI.emptySize();
      }

      Dimension dimension;
      if (isDecoratedMenu() && myCustomFrameTitlePane != null && myCustomFrameTitlePane.getComponent().isVisible()) {
        dimension = myCustomFrameTitlePane.getComponent().getPreferredSize();
      }
      else {
        dimension = JBUI.emptySize();
      }

      Dimension rd;
      if (contentPane != null) {
        rd = contentPane.getMaximumSize();
      }
      else {
        rd = new Dimension(Integer.MAX_VALUE,
                           Integer.MAX_VALUE - i.top - i.bottom - mbd.height - 1);
      }
      return new Dimension(Math.min(rd.width, mbd.width) + i.left + i.right + dimension.width,
                           rd.height + mbd.height + i.top + i.bottom + dimension.height);
    }

    @Override
    public void layoutContainer(Container parent) {
      Rectangle b = parent.getBounds();
      Insets i = getInsets();
      int w = b.width - i.right - i.left;
      int h = b.height - i.top - i.bottom;

      if (layeredPane != null) {
        layeredPane.setBounds(i.left, i.top, w, h);
      }
      if (glassPane != null) {
        glassPane.setBounds(i.left, i.top, w, h);
      }
      int contentY = 0;
      if (menuBar != null && menuBar.isVisible()) {
        Dimension mbd = menuBar.getPreferredSize();
        menuBar.setBounds(0, 0, w, mbd.height);
        if (!myFullScreen && !isDecoratedMenu()) {
          contentY += mbd.height;
        }
      }

      if (myCustomFrameTitlePane != null && myCustomFrameTitlePane.getComponent().isVisible()) {
        Dimension tpd = myCustomFrameTitlePane.getComponent().getPreferredSize();
        if (tpd != null) {
          int tpHeight = tpd.height;

          myCustomFrameTitlePane.getComponent().setBounds(0, 0, w, tpHeight);
          contentY += tpHeight;
        }
      }

      if (contentPane != null) {
        contentPane.setBounds(0, contentY, w, h - contentY);
      }
    }
  }

  /**
   * Returns true if menu should be placed in toolbar instead of menu bar
   */
  @ApiStatus.Internal
  public static boolean isMenuButtonInToolbar() {
    return SystemInfo.isLinux && ExperimentalUI.isNewUI() && FrameInfoHelper.isFloatingMenuBarSupported();
  }

  private static boolean isDecoratedMenu() {
    boolean osSupported = SystemInfo.isWindows ||
      (SystemInfo.isMac && ExperimentalUI.isNewUI() && Registry.is("ide.experimental.ui.title.toolbar.in.macos"));
    return (IdeFrameDecorator.isCustomDecorationActive() || MainToolbarKt.isToolbarInHeader()) && osSupported;
  }
}
