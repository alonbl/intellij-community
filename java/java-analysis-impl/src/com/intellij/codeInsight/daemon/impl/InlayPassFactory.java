/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.EditorBoundHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.codeInsight.folding.impl.ParameterNameFoldingManager;
import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.GraphicsConfig;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.ui.ColorUtil;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.GraphicsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;
import java.util.List;

public class InlayPassFactory extends AbstractProjectComponent implements TextEditorHighlightingPassFactory {
  public InlayPassFactory(Project project, TextEditorHighlightingPassRegistrar registrar) {
    super(project);
    registrar.registerTextEditorHighlightingPass(this, null, null, false, -1);
  }

  @Nullable
  @Override
  public TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
    return new InlayPass(file, editor);
  }

  private static class InlayPass extends EditorBoundHighlightingPass {
    private final Map<Integer, String> myAnnotations = new HashMap<>();

    private InlayPass(@NotNull PsiFile file, @NotNull Editor editor) {
      super(editor, file, true);
    }

    @Override
    public void doCollectInformation(@NotNull ProgressIndicator progress) {
      assert myDocument != null;
      myAnnotations.clear();
      if (!(myFile instanceof PsiJavaFile)) return;
      PsiJavaFile file = (PsiJavaFile) myFile;

      PsiClass[] classes = file.getClasses();
      for (PsiClass aClass : classes) {
        ProgressIndicatorProvider.checkCanceled();
        addElementsToFold(aClass);
      }
    }

    private void addElementsToFold(PsiClass aClass) {
      PsiElement[] children = aClass.getChildren();
      for (PsiElement child : children) {
        ProgressIndicatorProvider.checkCanceled();

        if (child instanceof PsiMethod) {
          PsiMethod method = (PsiMethod)child;
          PsiCodeBlock body = method.getBody();
          if (body != null) {
            addCodeBlockFolds(body);
          }
        }
        else if (child instanceof PsiField) {
          PsiField field = (PsiField)child;
          PsiExpression initializer = field.getInitializer();
          if (initializer != null) {
            addCodeBlockFolds(initializer);
          } else if (field instanceof PsiEnumConstant) {
            addCodeBlockFolds(field);
          }
        }
        else if (child instanceof PsiClassInitializer) {
          PsiClassInitializer initializer = (PsiClassInitializer)child;
          addCodeBlockFolds(initializer);
        }
        else if (child instanceof PsiClass) {
          addElementsToFold((PsiClass)child);
        }
      }
    }

    private void addCodeBlockFolds(PsiElement scope) {
      scope.accept(new JavaRecursiveElementWalkingVisitor() {
        @Override
        public void visitClass(PsiClass aClass) {
          addElementsToFold(aClass);
        }

        @Override
        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
          inlineLiteralArgumentsNames(expression);
          super.visitMethodCallExpression(expression);
        }

        @Override
        public void visitNewExpression(PsiNewExpression expression) {
          inlineLiteralArgumentsNames(expression);
          super.visitNewExpression(expression);
        }
      });
    }

    private void inlineLiteralArgumentsNames(@NotNull PsiCallExpression expression) {
      ParameterNameFoldingManager manager = new ParameterNameFoldingManager(expression);
      List<FoldingDescriptor> descriptors = manager.buildDescriptors();
      for (FoldingDescriptor descriptor : descriptors) {
        String text = descriptor.getPlaceholderText();
        assert text != null;
        int colonPos = text.indexOf(':');
        myAnnotations.put(descriptor.getRange().getStartOffset(), text.substring(0, colonPos));
      }
    }

    @Override
    public void doApplyInformationToEditor() {
      assert myDocument != null;
      Map<Integer, Integer> existingWidths = new HashMap<>();
      Set<String> removedHints = new HashSet<>();
      for (Inlay inlay : myEditor.getInlayModel().getElementsInRange(0, myDocument.getTextLength() + 1, Inlay.Type.INLINE)) {
        if (!(inlay.getRenderer() instanceof MyRenderer)) continue;
        int offset = inlay.getOffset();
        String oldText = ((MyRenderer)inlay.getRenderer()).myText;
        String newText = myAnnotations.get(offset);
        if (!Objects.equals(newText, oldText)) {
          if (newText == null) {
            removedHints.add(oldText);
          }
          else {
            existingWidths.put(offset, inlay.getWidthInPixels());
          }
          Disposer.dispose(inlay);
        }
        else {
          myAnnotations.remove(offset);
        }
      }
      for (Map.Entry<Integer, String> e : myAnnotations.entrySet()) {
        int offset = e.getKey();
        String text = e.getValue();
        MyRenderer renderer = new MyRenderer(text);
        if (removedHints.contains(text)) {
          myEditor.getInlayModel().addElement(offset, Inlay.Type.INLINE, renderer);
        }
        else {
          Integer oldWidth = existingWidths.get(offset);
          InlayAnimationManager.addElementWithAnimation(myEditor, offset, renderer, oldWidth == null ? 0 : oldWidth);
        }
      }
    }
  }

  private static class MyRenderer extends Inlay.Renderer {
    private static final FontInfo FONT = new FontInfo("Segoe UI", 11, Font.PLAIN);
    private final String myText;

    private MyRenderer(String text) {
      myText = text;
    }

    @Override
    public int calcWidthInPixels(@NotNull Editor editor) {
      return FONT.fontMetrics().stringWidth(myText) + 14;
    }

    @Override
    public void paint(@NotNull Graphics g, @NotNull Rectangle r, @NotNull Editor editor) {
      TextAttributes attributes = editor.getColorsScheme().getAttributes(JavaHighlightingColors.INLINE_PARAMETER_HINT);
      if (attributes != null) {
        GraphicsConfig config = GraphicsUtil.setupAAPainting(g);
        Color backgroundColor = attributes.getBackgroundColor();
        g.setColor(ColorUtil.brighter(backgroundColor, 1));
        g.fillRoundRect(r.x + 1, r.y + 1, r.width - 2, r.height - 4, 4, 4);
        g.setColor(ColorUtil.darker(backgroundColor, 1));
        g.fillRoundRect(r.x + 1, r.y + 3, r.width - 2, r.height - 4, 4, 4);
        g.setColor(backgroundColor);
        g.fillRoundRect(r.x + 1, r.y + 2, r.width - 2, r.height - 4, 4, 4);
        g.setColor(attributes.getForegroundColor());
        g.setFont(FONT.getFont());
        FontMetrics metrics = g.getFontMetrics();
        Shape savedClip = g.getClip();
        g.clipRect(r.x + 2, r.y + 3, r.width - 4, r.height - 6); // support drawing in smaller rectangle (used in animation)
        g.drawString(myText, r.x + 7, r.y + (r.height + metrics.getAscent() - metrics.getDescent()) / 2 - 1);
        g.setClip(savedClip);
        config.restore();
      }
    }
  }
}
