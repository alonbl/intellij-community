// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.rename

import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNewDeclarationNameValidator
import org.jetbrains.kotlin.idea.base.psi.KotlinPsiHeuristics
import org.jetbrains.kotlin.idea.caches.resolve.analyzeInContext
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.base.psi.copied
import org.jetbrains.kotlin.idea.base.util.and
import org.jetbrains.kotlin.idea.base.util.restrictToKotlinSources
import org.jetbrains.kotlin.idea.highlighter.markers.resolveDeclarationWithParents
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.refactoring.explicateAsText
import org.jetbrains.kotlin.idea.refactoring.getThisLabelName
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchOptions
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchParameters
import org.jetbrains.kotlin.idea.base.util.useScope
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.getAllAccessibleFunctions
import org.jetbrains.kotlin.idea.util.getAllAccessibleVariables
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.OverloadChecker
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getExplicitReceiverValue
import org.jetbrains.kotlin.resolve.calls.util.getImplicitReceiverValue
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.findClassifier
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.error.ErrorUtils
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal fun ResolvedCall<*>.noReceivers() = dispatchReceiver == null && extensionReceiver == null

internal fun PsiNamedElement.renderDescription(): String {
    val type = UsageViewUtil.getType(this)
    if (name == null || name!!.startsWith("<")) return type
    return "$type '$name'".trim()
}

internal fun PsiElement.representativeContainer(): PsiNamedElement? = when (this) {
    is KtDeclaration -> containingClassOrObject
        ?: getStrictParentOfType<KtNamedDeclaration>()
        ?: JavaPsiFacade.getInstance(project).findPackage(containingKtFile.packageFqName.asString())
    is PsiMember -> containingClass
    else -> null
}

internal fun DeclarationDescriptor.canonicalRender(): String = DescriptorRenderer.FQ_NAMES_IN_TYPES.render(this)

internal fun checkRedeclarations(
    declaration: KtNamedDeclaration,
    newName: String,
    result: MutableList<UsageInfo>,
    resolutionFacade: ResolutionFacade = declaration.getResolutionFacade(),
    descriptor: DeclarationDescriptor = declaration.unsafeResolveToDescriptor(resolutionFacade)
) {
    fun DeclarationDescriptor.isTopLevelPrivate(): Boolean =
        this is DeclarationDescriptorWithVisibility && visibility == DescriptorVisibilities.PRIVATE && containingDeclaration is PackageFragmentDescriptor

    fun isInSameFile(d1: DeclarationDescriptor, d2: DeclarationDescriptor): Boolean =
        (d1 as? DeclarationDescriptorWithSource)?.source?.getPsi()?.containingFile == (d2 as? DeclarationDescriptorWithSource)?.source
            ?.getPsi()?.containingFile

    fun MemberScope.findSiblingsByName(): List<DeclarationDescriptor> {
        val descriptorKindFilter = when (descriptor) {
            is ClassifierDescriptor -> DescriptorKindFilter.CLASSIFIERS
            is VariableDescriptor -> DescriptorKindFilter.VARIABLES
            is FunctionDescriptor -> DescriptorKindFilter.FUNCTIONS
            else -> return emptyList()
        }
        return getDescriptorsFiltered(descriptorKindFilter) { it.asString() == newName }.filter { it != descriptor }
    }

    fun getSiblingsWithNewName(): List<DeclarationDescriptor> {
        val containingDescriptor = descriptor.containingDeclaration

        if (descriptor is ValueParameterDescriptor) {
            return (containingDescriptor as CallableDescriptor).valueParameters.filter { it.name.asString() == newName }
        }

        if (descriptor is TypeParameterDescriptor) {
            val typeParameters = when (containingDescriptor) {
                is ClassDescriptor -> containingDescriptor.declaredTypeParameters
                is CallableDescriptor -> containingDescriptor.typeParameters
                else -> emptyList()
            }

            return SmartList<DeclarationDescriptor>().apply {
                typeParameters.filterTo(this) { it.name.asString() == newName }
                val containingDeclaration = (containingDescriptor as? DeclarationDescriptorWithSource)?.source?.getPsi() as? KtDeclaration
                    ?: return emptyList()
                val dummyVar = KtPsiFactory(containingDeclaration).createProperty("val foo: $newName")
                val outerScope = containingDeclaration.getResolutionScope()
                val context = dummyVar.analyzeInContext(outerScope, containingDeclaration)
                addIfNotNull(context[BindingContext.VARIABLE, dummyVar]?.type?.constructor?.declarationDescriptor)
            }
        }

        return when (containingDescriptor) {
            is ClassDescriptor -> containingDescriptor.unsubstitutedMemberScope.findSiblingsByName()
            is PackageFragmentDescriptor -> containingDescriptor.getMemberScope().findSiblingsByName().filter {
                it != descriptor && (!(descriptor.isTopLevelPrivate() || it.isTopLevelPrivate()) || isInSameFile(descriptor, it))
            }
            else -> {
                val block =
                    (descriptor as? DeclarationDescriptorWithSource)?.source?.getPsi()?.parent as? KtBlockExpression ?: return emptyList()
                block.statements.mapNotNull {
                    if (it.name != newName) return@mapNotNull null
                    val isAccepted = when (descriptor) {
                        is ClassDescriptor -> it is KtClassOrObject
                        is VariableDescriptor -> it is KtProperty
                        is FunctionDescriptor -> it is KtNamedFunction
                        else -> false
                    }
                    if (!isAccepted) return@mapNotNull null
                    (it as? KtDeclaration)?.unsafeResolveToDescriptor()
                }
            }
        }
    }

    val overloadChecker = when (descriptor) {
        is PropertyDescriptor,
        is FunctionDescriptor,
        is ClassifierDescriptor -> {

            @OptIn(FrontendInternals::class)
            val typeSpecificityComparator = resolutionFacade.getFrontendService(descriptor.module, TypeSpecificityComparator::class.java)
            OverloadChecker(typeSpecificityComparator)
        }
        else -> null
    }
    for (candidateDescriptor in getSiblingsWithNewName()) {
        val candidate = (candidateDescriptor as? DeclarationDescriptorWithSource)?.source?.getPsi() as? KtNamedDeclaration ?: continue
        if (overloadChecker != null && overloadChecker.isOverloadable(descriptor, candidateDescriptor)) continue
        val what = candidate.renderDescription()
        val where = candidate.representativeContainer()?.renderDescription() ?: continue
        val message = KotlinBundle.message("text.0.already.declared.in.1", what, where).capitalize()
        result += BasicUnresolvableCollisionUsageInfo(candidate, candidate, message)
    }
}

private fun LexicalScope.getRelevantDescriptors(
    declaration: PsiNamedElement,
    name: String
): Collection<DeclarationDescriptor> {
    val nameAsName = Name.identifier(name)
    return when (declaration) {
        is KtProperty, is KtParameter, is PsiField -> getAllAccessibleVariables(nameAsName)
        is KtNamedFunction -> getAllAccessibleFunctions(nameAsName)
        is KtClassOrObject, is PsiClass -> listOfNotNull(findClassifier(nameAsName, NoLookupLocation.FROM_IDE))
        else -> emptyList()
    }
}

fun reportShadowing(
    declaration: PsiNamedElement,
    elementToBindUsageInfoTo: PsiElement,
    candidateDescriptor: DeclarationDescriptor,
    refElement: PsiElement,
    result: MutableList<UsageInfo>
) {
    val candidate = DescriptorToSourceUtilsIde.getAnyDeclaration(declaration.project, candidateDescriptor) as? PsiNamedElement ?: return
    if (declaration.parent == candidate.parent) return
    val message = KotlinBundle.message(
        "text.0.will.be.shadowed.by.1",
        declaration.renderDescription(),
        candidate.renderDescription()
    ).capitalize()
    result += BasicUnresolvableCollisionUsageInfo(refElement, elementToBindUsageInfoTo, message)
}

// todo: break into smaller functions
private fun checkUsagesRetargeting(
    elementToBindUsageInfosTo: PsiElement,
    declaration: PsiNamedElement,
    name: String,
    isNewName: Boolean,
    accessibleDescriptors: Collection<DeclarationDescriptor>,
    originalUsages: MutableList<UsageInfo>,
    newUsages: MutableList<UsageInfo>
) {
    val usageIterator = originalUsages.listIterator()
    while (usageIterator.hasNext()) {
        val usage = usageIterator.next()
        val refElement = usage.element as? KtSimpleNameExpression ?: continue
        val context = refElement.analyze(BodyResolveMode.PARTIAL)
        val scope = refElement.parentsWithSelf
            .filterIsInstance<KtElement>()
            .mapNotNull { context[BindingContext.LEXICAL_SCOPE, it] }
            .firstOrNull()
            ?: continue

        if (scope.getRelevantDescriptors(declaration, name).isEmpty()) {
            if (declaration !is KtProperty && declaration !is KtParameter) continue
            if (Fe10KotlinNewDeclarationNameValidator(refElement.parent, refElement, KotlinNameSuggestionProvider.ValidatorTarget.VARIABLE)(name)) continue
        }

        val psiFactory = KtPsiFactory(declaration)

        val resolvedCall = refElement.getResolvedCall(context)
        if (resolvedCall == null) {
            val typeReference = refElement.getStrictParentOfType<KtTypeReference>() ?: continue
            val referencedClass = context[BindingContext.TYPE, typeReference]?.constructor?.declarationDescriptor ?: continue
            val referencedClassFqName = FqName(IdeDescriptorRenderers.SOURCE_CODE.renderClassifierName(referencedClass))
            val newFqName = if (isNewName) referencedClassFqName.parent().child(Name.identifier(name)) else referencedClassFqName
            val fakeVar = psiFactory.createDeclaration<KtProperty>("val __foo__: ${newFqName.asString()}")
            val newContext = fakeVar.analyzeInContext(scope, refElement)
            val referencedClassInNewContext = newContext[BindingContext.TYPE, fakeVar.typeReference!!]?.constructor?.declarationDescriptor
            val candidateText = referencedClassInNewContext?.canonicalRender()
            if (referencedClassInNewContext == null
                || ErrorUtils.isError(referencedClassInNewContext)
                || referencedClass.canonicalRender() == candidateText
                || accessibleDescriptors.any { it.canonicalRender() == candidateText }
            ) {
                usageIterator.set(UsageInfoWithFqNameReplacement(refElement, declaration, newFqName))
            } else {
                reportShadowing(declaration, elementToBindUsageInfosTo, referencedClassInNewContext, refElement, newUsages)
            }
            continue
        }

        val callExpression = resolvedCall.call.callElement as? KtExpression ?: continue
        val fullCallExpression = callExpression.getQualifiedExpressionForSelectorOrThis()

        val qualifiedExpression = if (resolvedCall.noReceivers()) {
            val resultingDescriptor = resolvedCall.resultingDescriptor
            val fqName = resultingDescriptor.importableFqName
                ?: (resultingDescriptor as? ClassifierDescriptor)?.let {
                    FqName(IdeDescriptorRenderers.SOURCE_CODE.renderClassifierName(it))
                }
                ?: continue
            if (fqName.parent().isRoot) {
                callExpression.copied()
            } else {
                psiFactory.createExpressionByPattern("${fqName.parent().asString()}.$0", callExpression)
            }
        } else {
            resolvedCall.getExplicitReceiverValue()?.let {
                fullCallExpression.copied()
            } ?: resolvedCall.getImplicitReceiverValue()?.let { implicitReceiver ->
                val expectedLabelName = implicitReceiver.declarationDescriptor.getThisLabelName()
                val implicitReceivers = scope.getImplicitReceiversHierarchy()
                val receiversWithExpectedName = implicitReceivers.filter {
                    it.value.type.constructor.declarationDescriptor?.getThisLabelName() == expectedLabelName
                }

                val canQualifyThis = receiversWithExpectedName.isEmpty()
                        || receiversWithExpectedName.size == 1 && (declaration !is KtClassOrObject || expectedLabelName != name)
                if (canQualifyThis) {
                    if (refElement.parent is KtCallableReferenceExpression) {
                        psiFactory.createExpressionByPattern("${implicitReceiver.explicateAsText()}::$0", callExpression)
                    } else {
                        psiFactory.createExpressionByPattern("${implicitReceiver.explicateAsText()}.$0", callExpression)
                    }
                } else {
                    val defaultReceiverClassText =
                        implicitReceivers.firstOrNull()?.value?.type?.constructor?.declarationDescriptor?.canonicalRender()
                    val canInsertUnqualifiedThis = accessibleDescriptors.any { it.canonicalRender() == defaultReceiverClassText }
                    if (canInsertUnqualifiedThis) {
                        psiFactory.createExpressionByPattern("this.$0", callExpression)
                    } else {
                        callExpression.copied()
                    }
                }
            }
            ?: continue
        }

        val newCallee = if (qualifiedExpression is KtCallableReferenceExpression) {
            qualifiedExpression.callableReference
        } else {
            qualifiedExpression.getQualifiedElementSelector() as? KtSimpleNameExpression ?: continue
        }
        if (isNewName) {
            newCallee.getReferencedNameElement().replace(psiFactory.createNameIdentifier(name))
        }

        qualifiedExpression.parentSubstitute = fullCallExpression.parent
        val newContext = qualifiedExpression.analyzeInContext(scope, refElement, DelegatingBindingTrace(context, ""))

        val newResolvedCall = newCallee.getResolvedCall(newContext)
        val candidateText = newResolvedCall?.candidateDescriptor?.getImportableDescriptor()?.canonicalRender()

        if (newResolvedCall != null
            && !accessibleDescriptors.any { it.canonicalRender() == candidateText }
            && resolvedCall.candidateDescriptor.canonicalRender() != candidateText
        ) {
            reportShadowing(declaration, elementToBindUsageInfosTo, newResolvedCall.candidateDescriptor, refElement, newUsages)
            continue
        }

        if (fullCallExpression !is KtQualifiedExpression) {
            usageIterator.set(UsageInfoWithReplacement(fullCallExpression, declaration, qualifiedExpression))
        }
    }
}

internal fun checkOriginalUsagesRetargeting(
    declaration: KtNamedDeclaration,
    newName: String,
    originalUsages: MutableList<UsageInfo>,
    newUsages: MutableList<UsageInfo>
) {
    val accessibleDescriptors = declaration.getResolutionScope().getRelevantDescriptors(declaration, newName)
    checkUsagesRetargeting(declaration, declaration, newName, true, accessibleDescriptors, originalUsages, newUsages)
}

internal fun checkNewNameUsagesRetargeting(
    declaration: KtNamedDeclaration,
    newName: String,
    newUsages: MutableList<UsageInfo>
) {
    val currentName = declaration.name ?: return
    val descriptor = declaration.unsafeResolveToDescriptor()

    if (declaration is KtParameter && !declaration.hasValOrVar()) {
        val ownerFunction = declaration.ownerFunction
        val searchScope = (if (ownerFunction is KtPrimaryConstructor) ownerFunction.containingClassOrObject else ownerFunction) ?: return

        val usagesByCandidate = LinkedHashMap<PsiElement, MutableList<UsageInfo>>()

        searchScope.accept(
            object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    if (expression.getReferencedName() != newName) return
                    val ref = expression.mainReference
                    val candidate = ref.resolve() as? PsiNamedElement ?: return
                    usagesByCandidate.getOrPut(candidate) { SmartList() }.add(MoveRenameUsageInfo(ref, candidate))
                }
            }
        )

        for ((candidate, usages) in usagesByCandidate) {
            checkUsagesRetargeting(candidate, declaration, currentName, false, listOf(descriptor), usages, newUsages)
            usages.filterIsInstanceTo<KtResolvableCollisionUsageInfo, MutableList<UsageInfo>>(newUsages)
        }

        return
    }

    val operator = declaration.isOperator()
    for (candidateDescriptor in declaration.getResolutionScope().getRelevantDescriptors(declaration, newName)) {
        val candidate = DescriptorToSourceUtilsIde.getAnyDeclaration(declaration.project, candidateDescriptor) as? PsiNamedElement
            ?: continue

        val searchParameters = KotlinReferencesSearchParameters(
            candidate,
            scope = candidate.useScope().restrictToKotlinSources() and declaration.useScope(),
            kotlinOptions = KotlinReferencesSearchOptions(searchForOperatorConventions = operator)
        )

        val usages = ReferencesSearch.search(searchParameters).mapTo(SmartList<UsageInfo>()) { MoveRenameUsageInfo(it, candidate) }
        checkUsagesRetargeting(candidate, declaration, currentName, false, listOf(descriptor), usages, newUsages)
        usages.filterIsInstanceTo<KtResolvableCollisionUsageInfo, MutableList<UsageInfo>>(newUsages)
    }
}

internal fun PsiElement?.isOperator(): Boolean {
    if (this !is KtNamedFunction || !KotlinPsiHeuristics.isPossibleOperator(this)) {
        return false
    }

    val resolveWithParents = resolveDeclarationWithParents(this as KtNamedFunction)
    return resolveWithParents.overriddenDescriptors.any {
        val psi = it.source.getPsi() ?: return@any false
        psi !is KtElement || psi.safeAs<KtNamedFunction>()?.hasModifier(KtTokens.OPERATOR_KEYWORD) == true
    }
}