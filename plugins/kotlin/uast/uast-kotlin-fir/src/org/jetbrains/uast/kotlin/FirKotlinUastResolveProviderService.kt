// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.uast.kotlin

import com.intellij.psi.*
import com.intellij.psi.impl.compiled.ClsMemberImpl
import com.intellij.psi.impl.file.PsiPackageImpl
import com.intellij.util.SmartList
import org.jetbrains.kotlin.analysis.api.KtTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.base.KtConstantValue
import org.jetbrains.kotlin.analysis.api.calls.*
import org.jetbrains.kotlin.analysis.api.components.KtConstantEvaluationMode
import org.jetbrains.kotlin.analysis.api.components.buildClassType
import org.jetbrains.kotlin.analysis.api.components.buildTypeParameterType
import org.jetbrains.kotlin.analysis.api.symbols.KtConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.analysis.project.structure.KtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.KtSourceModule
import org.jetbrains.kotlin.analysis.project.structure.getKtModule
import org.jetbrains.kotlin.asJava.toLightAnnotation
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.uast.*
import org.jetbrains.uast.kotlin.internal.*
import org.jetbrains.uast.kotlin.psi.UastKotlinPsiParameterBase

interface FirKotlinUastResolveProviderService : BaseKotlinUastResolveProviderService {

    override val languagePlugin: UastLanguagePlugin
        get() = firKotlinUastPlugin

    override val baseKotlinConverter: BaseKotlinConverter
        get() = FirKotlinConverter

    private val KtExpression.parentValueArgument: ValueArgument?
        get() = parents.firstOrNull { it is ValueArgument } as? ValueArgument

    override fun convertToPsiAnnotation(ktElement: KtElement): PsiAnnotation? {
        return ktElement.toLightAnnotation()
    }

    override fun convertValueArguments(ktCallElement: KtCallElement, parent: UElement): List<UNamedExpression>? {
        analyzeForUast(ktCallElement) {
            val argumentMapping = ktCallElement.resolveCall().singleFunctionCallOrNull()?.argumentMapping ?: return null
            val handledParameters = mutableSetOf<KtValueParameterSymbol>()
            val valueArguments = SmartList<UNamedExpression>()
            // NB: we need a loop over call element's value arguments to preserve their order.
            ktCallElement.valueArguments.forEach {
                val parameter = argumentMapping[it.getArgumentExpression()]?.symbol ?: return@forEach
                if (!handledParameters.add(parameter)) return@forEach
                val arguments = argumentMapping.entries
                    .filter { (_, param) -> param.symbol == parameter }
                    .mapNotNull { (arg, _) -> arg.parentValueArgument }
                val name = parameter.name.asString()
                when {
                    arguments.size == 1 ->
                        KotlinUNamedExpression.create(name, arguments.first(), parent)
                    arguments.size > 1 ->
                        KotlinUNamedExpression.create(name, arguments, parent)
                    else -> null
                }?.let { valueArgument -> valueArguments.add(valueArgument) }
            }
            return valueArguments.ifEmpty { null }
        }
    }

    override fun findAttributeValueExpression(uAnnotation: KotlinUAnnotation, arg: ValueArgument): UExpression? {
        val annotationEntry = uAnnotation.sourcePsi
        analyzeForUast(annotationEntry) {
            val resolvedAnnotationCall = annotationEntry.resolveCall().singleCallOrNull<KtAnnotationCall>() ?: return null
            val parameter = resolvedAnnotationCall.argumentMapping[arg.getArgumentExpression()]?.symbol ?: return null
            val namedExpression = uAnnotation.attributeValues.find { it.name == parameter.name.asString() }
            return namedExpression?.expression as? KotlinUVarargExpression ?: namedExpression
        }
    }

    override fun findDefaultValueForAnnotationAttribute(ktCallElement: KtCallElement, name: String): KtExpression? {
        analyzeForUast(ktCallElement) {
            val resolvedAnnotationConstructorSymbol =
                ktCallElement.resolveCall().singleConstructorCallOrNull()?.symbol ?: return null
            val parameter = resolvedAnnotationConstructorSymbol.valueParameters.find { it.name.asString() == name } ?: return null
            return (parameter.psi as? KtParameter)?.defaultValue
        }
    }

    override fun getArgumentForParameter(ktCallElement: KtCallElement, index: Int, parent: UElement): UExpression? {
        analyzeForUast(ktCallElement) {
            val resolvedFunctionCall = ktCallElement.resolveCall().singleFunctionCallOrNull()
            val resolvedFunctionLikeSymbol =
                resolvedFunctionCall?.symbol ?: return null
            val parameter = resolvedFunctionLikeSymbol.valueParameters.getOrNull(index) ?: return null
            val arguments = resolvedFunctionCall.argumentMapping.entries
                .filter { (_, param) -> param.symbol == parameter }
                .mapNotNull { (arg, _) -> arg.parentValueArgument }
            return when {
                arguments.isEmpty() -> null
                arguments.size == 1 -> {
                    val argument = arguments.single()
                    if (parameter.isVararg && argument.getSpreadElement() == null)
                        baseKotlinConverter.createVarargsHolder(arguments, parent)
                    else
                        baseKotlinConverter.convertOrEmpty(argument.getArgumentExpression(), parent)
                }
                else ->
                    baseKotlinConverter.createVarargsHolder(arguments, parent)
            }
        }
    }

    override fun getImplicitReturn(ktLambdaExpression: KtLambdaExpression, parent: UElement): KotlinUImplicitReturnExpression? {
        val lastExpression = ktLambdaExpression.bodyExpression?.statements?.lastOrNull() ?: return null
        // Skip _explicit_ return.
        if (lastExpression is KtReturnExpression) return null
        analyzeForUast(ktLambdaExpression) {
            // TODO: Should check an explicit, expected return type as well
            //  e.g., val y: () -> Unit = { 1 } // the lambda return type is Int, but we won't add an implicit return here.
            val returnType = ktLambdaExpression.functionLiteral.getAnonymousFunctionSymbol().returnType
            val returnUnitOrNothing = returnType.isUnit || returnType.isNothing
            return if (returnUnitOrNothing) null else
                KotlinUImplicitReturnExpression(parent).apply {
                    returnExpression = baseKotlinConverter.convertOrEmpty(lastExpression, this)
                }
        }
    }

    override fun getImplicitParameters(
        ktLambdaExpression: KtLambdaExpression,
        parent: UElement,
        includeExplicitParameters: Boolean
    ): List<KotlinUParameter> {
        // TODO receiver parameter, dispatch parameter like in org.jetbrains.uast.kotlin.KotlinUastResolveProviderService.getImplicitParameters
        analyzeForUast(ktLambdaExpression) {
            return ktLambdaExpression.functionLiteral.getAnonymousFunctionSymbol().valueParameters.map { p ->
                val psiType = p.returnType.asPsiType(
                    ktLambdaExpression,
                    KtTypeMappingMode.DEFAULT_UAST,
                    isAnnotationMethod = false
                ) ?: UastErrorType
                KotlinUParameter(
                    UastKotlinPsiParameterBase(
                        name = p.name.asString(),
                        type = psiType,
                        parent = ktLambdaExpression,
                        ktOrigin = ktLambdaExpression,
                        language = ktLambdaExpression.language,
                        isVarArgs = p.isVararg,
                        ktDefaultValue = null
                    ),
                    null,
                    parent
                )
            }
        }
    }

    override fun getPsiAnnotations(psiElement: PsiModifierListOwner): Array<PsiAnnotation> {
        return psiElement.annotations
    }

    override fun resolveBitwiseOperators(ktBinaryExpression: KtBinaryExpression): UastBinaryOperator {
        val other = UastBinaryOperator.OTHER
        analyzeForUast(ktBinaryExpression) {
            val resolvedCall = ktBinaryExpression.resolveCall()?.singleFunctionCallOrNull() ?: return other
            val operatorName = resolvedCall.symbol.callableIdIfNonLocal?.callableName?.asString() ?: return other
            return KotlinUBinaryExpression.BITWISE_OPERATORS[operatorName] ?: other
        }
    }

    override fun resolveCall(ktElement: KtElement): PsiMethod? {
        return analyzeForUast(ktElement) {
            ktElement.resolveCall()?.singleFunctionCallOrNull()?.symbol?.let { toPsiMethod(it, ktElement) }
        }
    }

    override fun resolveAccessorCall(ktSimpleNameExpression: KtSimpleNameExpression): PsiMethod? {
        return analyzeForUast(ktSimpleNameExpression) {
            val variableAccessCall = ktSimpleNameExpression.resolveCall()?.singleCallOrNull<KtSimpleVariableAccessCall>() ?: return null
            val propertySymbol = variableAccessCall.symbol as? KtPropertySymbol ?: return null
            when (variableAccessCall.simpleAccess) {
                is KtSimpleVariableAccess.Read ->
                    toPsiMethod(propertySymbol.getter ?: return null, ktSimpleNameExpression)
                is KtSimpleVariableAccess.Write ->
                    toPsiMethod(propertySymbol.setter ?: return null, ktSimpleNameExpression)
            }
        }
    }

    override fun isResolvedToExtension(ktCallElement: KtCallElement): Boolean {
        analyzeForUast(ktCallElement) {
            val resolvedFunctionLikeSymbol = ktCallElement.resolveCall().singleFunctionCallOrNull()?.symbol ?: return false
            return resolvedFunctionLikeSymbol.isExtension
        }
    }

    override fun resolvedFunctionName(ktCallElement: KtCallElement): String? {
        analyzeForUast(ktCallElement) {
            val resolvedFunctionLikeSymbol = ktCallElement.resolveCall().singleFunctionCallOrNull()?.symbol ?: return null
            return (resolvedFunctionLikeSymbol as? KtNamedSymbol)?.name?.identifierOrNullIfSpecial
                ?: (resolvedFunctionLikeSymbol as? KtConstructorSymbol)?.let { SpecialNames.INIT.asString() }
        }
    }

    override fun qualifiedAnnotationName(ktCallElement: KtCallElement): String? {
        analyzeForUast(ktCallElement) {
            val resolvedAnnotationConstructorSymbol =
                ktCallElement.resolveCall().singleConstructorCallOrNull()?.symbol ?: return null
            return resolvedAnnotationConstructorSymbol.containingClassIdIfNonLocal
                ?.asSingleFqName()
                ?.toString()
        }
    }

    override fun callKind(ktCallElement: KtCallElement): UastCallKind {
        analyzeForUast(ktCallElement) {
            val resolvedFunctionLikeSymbol =
                ktCallElement.resolveCall().singleFunctionCallOrNull()?.symbol ?: return UastCallKind.METHOD_CALL
            val fqName = resolvedFunctionLikeSymbol.callableIdIfNonLocal?.asSingleFqName()
            return when {
                resolvedFunctionLikeSymbol is KtConstructorSymbol -> UastCallKind.CONSTRUCTOR_CALL
                fqName != null && isAnnotationArgumentArrayInitializer(ktCallElement, fqName) -> UastCallKind.NESTED_ARRAY_INITIALIZER
                else -> UastCallKind.METHOD_CALL
            }
        }
    }

    override fun isAnnotationConstructorCall(ktCallElement: KtCallElement): Boolean {
        analyzeForUast(ktCallElement) {
            val resolvedAnnotationConstructorSymbol =
                ktCallElement.resolveCall().singleConstructorCallOrNull()?.symbol ?: return false
            val ktType = resolvedAnnotationConstructorSymbol.returnType
            val context = containingKtClass(resolvedAnnotationConstructorSymbol) ?: ktCallElement
            val psiClass = toPsiClass(ktType, null, context, ktCallElement.typeOwnerKind) ?: return false
            return psiClass.isAnnotationType
        }
    }

    override fun resolveToClassIfConstructorCall(ktCallElement: KtCallElement, source: UElement): PsiClass? {
        analyzeForUast(ktCallElement) {
            val resolvedFunctionLikeSymbol = ktCallElement.resolveCall().singleFunctionCallOrNull()?.symbol ?: return null
            return when (resolvedFunctionLikeSymbol) {
                is KtConstructorSymbol -> {
                    val context = containingKtClass(resolvedFunctionLikeSymbol) ?: ktCallElement
                    toPsiClass(resolvedFunctionLikeSymbol.returnType, source, context, ktCallElement.typeOwnerKind)
                }
                is KtSamConstructorSymbol -> {
                    toPsiClass(resolvedFunctionLikeSymbol.returnType, source, ktCallElement, ktCallElement.typeOwnerKind)
                }
                else -> null
            }
        }
    }

    override fun resolveToClass(ktAnnotationEntry: KtAnnotationEntry, source: UElement): PsiClass? {
        analyzeForUast(ktAnnotationEntry) {
            val resolvedAnnotationCall = ktAnnotationEntry.resolveCall().singleCallOrNull<KtAnnotationCall>() ?: return null
            val resolvedAnnotationConstructorSymbol = resolvedAnnotationCall.symbol
            val ktType = resolvedAnnotationConstructorSymbol.returnType
            val context = containingKtClass(resolvedAnnotationConstructorSymbol) ?: ktAnnotationEntry
            return toPsiClass(ktType, source, context, ktAnnotationEntry.typeOwnerKind)
        }
    }

    override fun resolveToDeclaration(ktExpression: KtExpression): PsiElement? {
        val resolvedTargetSymbol = when (ktExpression) {
            is KtExpressionWithLabel -> {
                analyzeForUast(ktExpression) {
                    ktExpression.getTargetLabel()?.mainReference?.resolveToSymbol()
                }
            }
            is KtCallExpression -> {
                resolveCall(ktExpression)?.let { return it }
            }
            is KtReferenceExpression -> {
                analyzeForUast(ktExpression) {
                    ktExpression.mainReference.resolveToSymbol()
                }
            }
            else -> null
        } ?: return null

        val resolvedTargetElement = resolvedTargetSymbol.psiForUast(ktExpression.project)

        // Shortcut: if the resolution target is compiled class/member, package info, or pure Java declarations,
        //   we can return it early here (to avoid expensive follow-up steps: module retrieval and light element conversion).
        if (resolvedTargetElement is ClsMemberImpl<*> ||
            resolvedTargetElement is PsiPackageImpl ||
            !isKotlin(resolvedTargetElement)
        ) {
            return resolvedTargetElement
        }

        when ((resolvedTargetElement as? KtDeclaration)?.getKtModule(ktExpression.project)) {
            is KtSourceModule -> {
                // `getMaybeLightElement` tries light element conversion first, and then something else for local declarations.
                resolvedTargetElement?.getMaybeLightElement(ktExpression)?.let { return it }
            }
            is KtLibraryModule -> {
                // For decompiled declarations, we can try light element conversion (only).
                (resolvedTargetElement as? KtDeclaration)?.toLightElements()?.singleOrNull()?.let { return it }
            }
            else -> {}
        }

        fun resolveToPsiClassOrEnumEntry(classOrObject: KtClassOrObject): PsiElement? {
            analyzeForUast(ktExpression) {
                val ktType = when (classOrObject) {
                    is KtEnumEntry ->
                        classOrObject.getEnumEntrySymbol().callableIdIfNonLocal?.classId?.let { enumClassId ->
                            buildClassType(enumClassId)
                        }
                    else ->
                        buildClassType(classOrObject.getClassOrObjectSymbol())
                } ?: return null
                val psiClass = toPsiClass(ktType, source = null, classOrObject, classOrObject.typeOwnerKind)
                return when (classOrObject) {
                    is KtEnumEntry -> psiClass?.findFieldByName(classOrObject.name, false)
                    else -> psiClass
                }
            }
        }

        when (resolvedTargetElement) {
            is KtClassOrObject -> {
                resolveToPsiClassOrEnumEntry(resolvedTargetElement)?.let { return it }
            }
            is KtConstructor<*> -> {
                resolveToPsiClassOrEnumEntry(resolvedTargetElement.getContainingClassOrObject())?.let { return it }
            }
            is KtTypeAlias -> {
                analyzeForUast(ktExpression) {
                    val ktType = resolvedTargetElement.getTypeAliasSymbol().expandedType
                    toPsiClass(
                        ktType,
                        source = null,
                        resolvedTargetElement,
                        resolvedTargetElement.typeOwnerKind
                    )?.let { return it }
                }
            }
            is KtTypeParameter -> {
                analyzeForUast(ktExpression) {
                    val ktType = buildTypeParameterType(resolvedTargetElement.getTypeParameterSymbol())
                    toPsiClass(
                        ktType,
                        ktExpression.toUElement(),
                        resolvedTargetElement,
                        resolvedTargetElement.typeOwnerKind
                    )?.let { return it }
                }
            }
            is KtFunctionLiteral -> {
                // Implicit lambda parameter `it`
                if ((resolvedTargetSymbol as? KtValueParameterSymbol)?.isImplicitLambdaParameter == true) {
                    // From its containing lambda (of function literal), build ULambdaExpression
                    val lambda = resolvedTargetElement.toUElementOfType<ULambdaExpression>()
                    // and return javaPsi of the corresponding lambda implicit parameter
                    lambda?.valueParameters?.singleOrNull()?.javaPsi?.let { return it }
                }
            }
        }

        // TODO: need to handle resolved target to library source
        return resolvedTargetElement
    }

    override fun resolveToType(ktTypeReference: KtTypeReference, source: UElement, boxed: Boolean): PsiType? {
        analyzeForUast(ktTypeReference) {
            val ktType = ktTypeReference.getKtType()
            if (ktType is KtClassErrorType) return null
            return toPsiType(ktType, source, ktTypeReference, ktTypeReference.typeOwnerKind, boxed)
        }
    }

    override fun resolveToType(ktTypeReference: KtTypeReference, containingLightDeclaration: PsiModifierListOwner?): PsiType? {
        analyzeForUast(ktTypeReference) {
            val ktType = ktTypeReference.getKtType()
            if (ktType is KtClassErrorType) return null
            return toPsiType(ktType, containingLightDeclaration, ktTypeReference, ktTypeReference.typeOwnerKind)
        }
    }

    override fun getReceiverType(ktCallElement: KtCallElement, source: UElement): PsiType? {
        analyzeForUast(ktCallElement) {
            val ktCall = ktCallElement.resolveCall().singleFunctionCallOrNull() ?: return null
            val ktType = ktCall.partiallyAppliedSymbol.signature.receiverType ?: return null
            if (ktType is KtClassErrorType) return null
            return toPsiType(ktType, source, ktCallElement, ktCallElement.typeOwnerKind, boxed = true)
        }
    }

    override fun getAccessorReceiverType(ktSimpleNameExpression: KtSimpleNameExpression, source: UElement): PsiType? {
        analyzeForUast(ktSimpleNameExpression) {
            val ktCall = ktSimpleNameExpression.resolveCall()?.singleCallOrNull<KtVariableAccessCall>() ?: return null
            val ktType = ktCall.partiallyAppliedSymbol.signature.receiverType ?: return null
            if (ktType is KtClassErrorType) return null
            return toPsiType(ktType, source, ktSimpleNameExpression, ktSimpleNameExpression.typeOwnerKind, boxed = true)
        }
    }

    override fun getDoubleColonReceiverType(ktDoubleColonExpression: KtDoubleColonExpression, source: UElement): PsiType? {
        analyzeForUast(ktDoubleColonExpression) {
            val receiverKtType = ktDoubleColonExpression.getReceiverKtType() ?: return null
            return toPsiType(receiverKtType, source, ktDoubleColonExpression, ktDoubleColonExpression.typeOwnerKind, boxed = true)
        }
    }

    override fun getCommonSupertype(left: KtExpression, right: KtExpression, uExpression: UExpression): PsiType? {
        val ktElement = uExpression.sourcePsi as? KtExpression ?: return null
        analyzeForUast(ktElement) {
            val leftType = left.getKtType() ?: return null
            val rightType = right.getKtType()  ?: return null
            val commonSuperType = commonSuperType(listOf(leftType, rightType)) ?: return null
            return toPsiType(commonSuperType, uExpression, ktElement, ktElement.typeOwnerKind)
        }
    }

    override fun getType(ktExpression: KtExpression, source: UElement): PsiType? {
        analyzeForUast(ktExpression) {
            val ktType = ktExpression.getKtType() ?: return null
            return toPsiType(ktType, source, ktExpression, ktExpression.typeOwnerKind)
        }
    }

    override fun getType(ktDeclaration: KtDeclaration, source: UElement): PsiType? {
        analyzeForUast(ktDeclaration) {
            return toPsiType(ktDeclaration.getReturnKtType(), source, ktDeclaration, ktDeclaration.typeOwnerKind)
        }
    }

    override fun getType(ktDeclaration: KtDeclaration, containingLightDeclaration: PsiModifierListOwner?): PsiType? {
        analyzeForUast(ktDeclaration) {
            return toPsiType(ktDeclaration.getReturnKtType(), containingLightDeclaration, ktDeclaration, ktDeclaration.typeOwnerKind)
        }
    }

    override fun getFunctionType(ktFunction: KtFunction, source: UElement): PsiType? {
        analyzeForUast(ktFunction) {
            return toPsiType(ktFunction.getFunctionalType(), source, ktFunction, ktFunction.typeOwnerKind)
        }
    }

    override fun getFunctionalInterfaceType(uLambdaExpression: KotlinULambdaExpression): PsiType? {
        val sourcePsi = uLambdaExpression.sourcePsi
        analyzeForUast(sourcePsi) {
            val samType = sourcePsi.getExpectedType()
                ?.takeIf { it !is KtClassErrorType && it.isFunctionalInterfaceType }
                ?.lowerBoundIfFlexible()
                ?: return null
            return toPsiType(samType, uLambdaExpression, sourcePsi, sourcePsi.typeOwnerKind)
        }
    }

    override fun nullability(psiElement: PsiElement): TypeNullability? {
        if (psiElement is KtTypeReference) {
            analyzeForUast(psiElement) {
                nullability(psiElement.getKtType())?.let { return it }
            }
        }
        if (psiElement is KtCallableDeclaration) {
            psiElement.typeReference?.let { typeReference ->
                analyzeForUast(typeReference) {
                    nullability(typeReference.getKtType())?.let { return it }
                }
            }
        }
        if (psiElement is KtProperty) {
            psiElement.initializer?.let { propertyInitializer ->
                analyzeForUast(propertyInitializer) {
                    nullability(propertyInitializer.getKtType())?.let { return it }
                }
            }
            psiElement.delegateExpression?.let { delegatedExpression ->
                analyzeForUast(delegatedExpression) {
                    val typeArgument = (delegatedExpression.getKtType() as? KtNonErrorClassType)?.typeArguments?.firstOrNull()
                    nullability((typeArgument as? KtTypeArgumentWithVariance)?.type)?.let { return it }
                }
            }
        }
        psiElement.getParentOfType<KtProperty>(false)?.let { property ->
            property.typeReference?.let { typeReference ->
                analyzeForUast(typeReference) {
                    nullability(typeReference.getKtType())
                }
            } ?:
            property.initializer?.let { propertyInitializer ->
                analyzeForUast(propertyInitializer) {
                    nullability(propertyInitializer.getKtType())
                }
            }
        }?.let { return it }
        return null
    }

    override fun evaluate(uExpression: UExpression): Any? {
        val ktExpression = uExpression.sourcePsi as? KtExpression ?: return null
        analyzeForUast(ktExpression) {
            return ktExpression.evaluate(KtConstantEvaluationMode.CONSTANT_LIKE_EXPRESSION_EVALUATION)
                ?.takeUnless { it is KtConstantValue.KtErrorConstantValue }?.value
        }
    }
}
