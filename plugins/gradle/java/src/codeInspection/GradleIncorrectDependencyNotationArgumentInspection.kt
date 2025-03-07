// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.codeInspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.CommonClassNames.*
import com.intellij.psi.util.InheritanceUtil.isInheritor
import com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.plugins.gradle.config.GradleFileType
import org.jetbrains.plugins.gradle.service.resolve.GradleCommonClassNames.*
import org.jetbrains.plugins.gradle.service.resolve.GradleDependencyHandlerContributor
import org.jetbrains.plugins.groovy.codeInspection.GroovyLocalInspectionTool
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames.GROOVY_LANG_CLOSURE
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames.GROOVY_LANG_GSTRING
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyConstantExpressionEvaluator

/**
 * It is possible to implement this inspection by specifying overloads to configuration methods,
 * but it would greatly pollute the completion list, since there are 12 overloads to each of the configuration.
 * Also, we can provide custom parsing of string and map literals right here.
 */
class GradleIncorrectDependencyNotationArgumentInspection : GroovyLocalInspectionTool() {

  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    return if (file.fileType == GradleFileType) super.checkFile(file, manager, isOnTheFly) else null
  }

  override fun buildGroovyVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): GroovyElementVisitor = object : GroovyElementVisitor() {
    override fun visitMethodCall(call: GrMethodCall) {
      val method = call.resolveMethod() ?: return
      if (method !is OriginInfoAwareElement || method.originInfo != GradleDependencyHandlerContributor.DEPENDENCY_NOTATION) {
        return
      }
      val arguments = call.argumentList.expressionArguments
      val namedArguments = call.namedArguments
      if (arguments.isEmpty() && namedArguments.isEmpty()) {
        holder.registerProblem(call.argumentList, GradleInspectionBundle.message("inspection.display.name.dependency.notation.required"), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        return
      }
      if (namedArguments.isNotEmpty()) {
        checkNamedArguments(namedArguments.asList(), call.argumentList, holder)
      }
      if (arguments.isNotEmpty()) {
        val argumentsToCheck = getObservableArguments(call, arguments)
        for (argument in argumentsToCheck) {
          val type = argument.type
          val providerComponentType = extractComponentType(argument, type, GRADLE_API_PROVIDER_PROVIDER)
          val iterableComponentType = extractComponentType(argument, providerComponentType, JAVA_LANG_ITERABLE)
          checkArgument(argument, iterableComponentType, holder)
        }
      }
    }
  }

  private fun checkArgument(argument: GrExpression, type: PsiType?, holder: ProblemsHolder) {
    when {
      type == null -> Unit
      isInheritor(type, GRADLE_API_ARTIFACTS_DEPENDENCY) -> return
      isInheritor(type, JAVA_LANG_CHAR_SEQUENCE) || type.equalsToText(GROOVY_LANG_GSTRING) -> return checkString(argument, holder)
      isInheritor(type, GRADLE_API_ARTIFACTS_MINIMAL_EXTERNAL_MODULE_DEPENDENCY) -> return
      isInheritor(type, JAVA_UTIL_MAP) -> return checkMap(argument, holder)
      isInheritor(type, GRADLE_API_FILE_FILE_COLLECTION) -> return
      isInheritor(type, GRADLE_API_PROJECT) -> return
      isInheritor(type, "org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory.ClassPathNotation") -> return
    }
    holder.registerProblem(argument, GradleInspectionBundle.message("inspection.display.name.unrecognized.dependency.notation"))
  }

  private fun extractComponentType(context: PsiElement, type: PsiType?, fqn: String): PsiType? {
    if (isInheritor(type, GRADLE_API_FILE_FILE_COLLECTION) ||  !isInheritor(type, fqn)) {
      return type
    }
    val providerClass =
      JavaPsiFacade.getInstance(context.project).findClass(fqn, context.resolveScope) ?: return type
    val conversion = TypeConversionUtil.getSuperClassSubstitutor(providerClass, type as PsiClassType)
    return conversion.substitutionMap.values.firstOrNull() ?: type
  }

  private fun checkMap(argument: GrExpression, holder: ProblemsHolder) {
    if (argument is GrListOrMap) {
      checkNamedArguments(argument.namedArguments.asList(), argument, holder)
    }
  }

  private fun checkNamedArguments(arguments: List<GrNamedArgument>, place: PsiElement, holder: ProblemsHolder) {
    var hasName = false
    var hasGroup = false
    var hasVersion = false
    for (argument in arguments) {
      when (argument.labelName) {
        null -> return
        "name" -> hasName = true
        "group" -> hasGroup = true
        "version" -> hasVersion = true
      }
    }
    if (!hasName) holder.registerProblem(place, GradleInspectionBundle.message("inspection.display.name.label.0.is.required", "name"))
    if (!hasGroup) holder.registerProblem(place, GradleInspectionBundle.message("inspection.display.name.label.0.is.required", "group"))
    if (!hasVersion) holder.registerProblem(place, GradleInspectionBundle.message("inspection.display.name.label.0.is.required", "version"))
  }

  private fun checkString(argument: GrExpression, holder: ProblemsHolder) {
    val string = GroovyConstantExpressionEvaluator.evaluate(argument)
    if (string is String) {
      val parts = string.split(":")
      if (parts.size != 3) {
        holder.registerProblem(argument, GradleInspectionBundle.message("inspection.display.name.expected.3.parts.separated.by.colon"))
      }
    }
  }

  private fun getObservableArguments(call: GrMethodCall, arguments: Array<out GrExpression>): List<GrExpression> {
    return if (!call.hasClosureArguments() &&
               isInheritor(arguments.last().type, GROOVY_LANG_CLOSURE)) {
      arguments.asList().subList(0, arguments.lastIndex)
    }
    else {
      arguments.asList()
    }
  }

}