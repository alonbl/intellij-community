package com.jetbrains.python.intelliLang;

import com.intellij.lang.Language;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.jetbrains.python.patterns.PythonPatterns;
import com.jetbrains.python.psi.PyElement;
import com.jetbrains.python.psi.PyStringLiteralExpression;
import org.intellij.plugins.intelliLang.inject.AbstractLanguageInjectionSupport;
import org.intellij.plugins.intelliLang.inject.config.BaseInjection;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author yole
 */
public class PyLanguageInjectionSupport extends AbstractLanguageInjectionSupport {
  @NonNls private static final String SUPPORT_ID = "python";

  @NotNull
  @Override
  public String getId() {
    return SUPPORT_ID;
  }

  @NotNull
  @Override
  public Class[] getPatternClasses() {
    return new Class[] { PythonPatterns.class };
  }

  @Override
  public boolean isApplicableTo(PsiLanguageInjectionHost host) {
    return host instanceof PyElement;
  }

  @Override
  public boolean useDefaultInjector(PsiLanguageInjectionHost host) {
    return true;
  }

  @Override
  public BaseInjection createInjection(Element element) {
    // This is how DefaultLanguageInjector gets its injection ranges
    return new BaseInjection(getId()) {
      @NotNull
      @Override
      public List<TextRange> getInjectedArea(PsiElement element) {
        if (element instanceof PyStringLiteralExpression) {
          return ((PyStringLiteralExpression)element).getStringValueTextRanges();
        }
        return super.getInjectedArea(element);
      }
    };
  }

  @Override
  public boolean addInjectionInPlace(Language language, PsiLanguageInjectionHost psiElement) {
    // XXX: Disable temporary injections via intention actions for Python elements, since TemporaryPlacesInjector cannot handle elements
    // with multiple injection text ranges (PY-10691)
    return true;
  }
}
