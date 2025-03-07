// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.uiDesigner.actions;

import com.intellij.CommonBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.uiDesigner.FormEditingUtil;
import com.intellij.uiDesigner.UIDesignerBundle;
import com.intellij.uiDesigner.designSurface.GuiEditor;
import com.intellij.uiDesigner.lw.LwComponent;
import com.intellij.uiDesigner.lw.LwContainer;
import com.intellij.uiDesigner.lw.LwRootContainer;
import com.intellij.uiDesigner.wizard.DataBindingWizard;
import com.intellij.uiDesigner.wizard.Generator;
import com.intellij.uiDesigner.wizard.WizardData;
import org.jetbrains.annotations.NotNull;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class DataBindingWizardAction extends AnAction{
  private static final Logger LOG = Logger.getInstance(DataBindingWizardAction.class);

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project;
    final VirtualFile formFile;
    GuiEditor editor = FormEditingUtil.getActiveEditor(e.getDataContext());
    if (editor == null) {
      return;
    }
    project = editor.getProject();
    formFile = editor.getFile();

    try {
      final WizardData wizardData = new WizardData(project, formFile);


      final Module module = ModuleUtilCore.findModuleForFile(formFile, wizardData.myProject);
      LOG.assertTrue(module != null);

      final LwRootContainer[] rootContainer = new LwRootContainer[1];
      Generator.exposeForm(wizardData.myProject, formFile, rootContainer);
      final String classToBind = rootContainer[0].getClassToBind();
      if (classToBind == null) {
        Messages.showInfoMessage(
          project,
          UIDesignerBundle.message("info.form.not.bound"),
          UIDesignerBundle.message("title.data.binding.wizard")
        );
        return;
      }

      final PsiClass boundClass = FormEditingUtil.findClassToBind(module, classToBind);
      if(boundClass == null){
        Messages.showErrorDialog(
          project,
          UIDesignerBundle.message("error.bound.to.not.found.class", classToBind),
          UIDesignerBundle.message("title.data.binding.wizard")
        );
        return;
      }

      Generator.prepareWizardData(wizardData, boundClass);

      if(!hasBinding(rootContainer[0])){
        Messages.showInfoMessage(
          project,
          UIDesignerBundle.message("info.no.bound.components"),
          UIDesignerBundle.message("title.data.binding.wizard")
        );
        return;
      }

      if (!wizardData.myBindToNewBean) {
        final String[] variants = new String[]{UIDesignerBundle.message("action.alter.data.binding"),
          UIDesignerBundle.message("action.bind.to.another.bean"), CommonBundle.getCancelButtonText()};
        final int result = Messages.showYesNoCancelDialog(
          project,
          UIDesignerBundle.message("info.data.binding.regenerate", wizardData.myBeanClass.getQualifiedName()),
          UIDesignerBundle.message("title.data.binding"),
          variants[0], variants[1], variants[2],
          Messages.getQuestionIcon()
        );
        if (result == Messages.YES) {
          // do nothing here
        }
        else if (result == Messages.NO) {
          wizardData.myBindToNewBean = true;
        }
        else {
          return;
        }
      }

      final DataBindingWizard wizard = new DataBindingWizard(project, wizardData);
      wizard.show();
    }
    catch (Generator.MyException exc) {
      Messages.showErrorDialog(
        project,
        exc.getMessage(),
        CommonBundle.getErrorTitle()
      );
    }
  }

  @Override
  public void update(@NotNull final AnActionEvent e) {
    e.getPresentation().setVisible(FormEditingUtil.getActiveEditor(e.getDataContext()) != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }


  private static boolean hasBinding(final LwComponent component) {
    if (component.getBinding() != null) {
      return true;
    }

    if (component instanceof LwContainer) {
      final LwContainer container = (LwContainer)component;
      for (int i=0; i < container.getComponentCount(); i++) {
        if (hasBinding((LwComponent)container.getComponent(i))) {
          return true;
        }
      }
    }

    return false;
  }
}
