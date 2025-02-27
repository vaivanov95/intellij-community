// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.projectView

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.AbstractPsiBasedNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.psi.*

@ApiStatus.Internal
class KtDeclarationTreeNode private constructor(
    project: Project?,
    val declaration: KtDeclaration?,
    viewSettings: ViewSettings?
) : AbstractPsiBasedNode<KtDeclaration?>(project, declaration!!, viewSettings) {

    override fun extractPsiFromValue(): PsiElement? = value

    override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> = emptyList()

    override fun updateImpl(data: PresentationData) {
        val declaration = value ?: return
        data.presentableText = tryGetRepresentableText(declaration)
    }

    override fun isDeprecated(): Boolean = value?.let { KtPsiUtil.isDeprecated(it) } ?: false

    companion object {
        private val CLASS_INITIALIZER = "<" + KotlinBundle.message("project.view.class.initializer") + ">"
        private val EXPRESSION =  "<" + KotlinBundle.message("project.view.expression") + ">"
        private val ERROR_NAME = "<" + KotlinBundle.message("project.view.class.error.name") + ">"

        private fun String?.orErrorName() = if (!isNullOrBlank()) this else ERROR_NAME

        @ApiStatus.Internal
        @NlsSafe
        fun tryGetRepresentableText(declaration: KtDeclaration, renderReceiverType: Boolean = true, renderArguments: Boolean = true, renderReturnType: Boolean = true): String {

            fun KtProperty.presentableText() = buildString {
                append(name.orErrorName())
                if (renderReturnType) {
                    typeReference?.text?.let { reference ->
                        append(": ")
                        append(reference)
                    }
                }
            }

            fun KtFunction.presentableText(): String {
                val func = this
                return buildString {
                    if (renderReceiverType) {
                        receiverTypeReference?.text?.let { receiverReference ->
                            append(receiverReference)
                            append('.')
                        }
                    }
                    if (func is KtFunctionLiteral) {
                        val calleeExpression = (func.parent.parent.parent as? KtCallExpression)?.calleeExpression
                        val name =
                            ((calleeExpression as? KtNameReferenceExpression)?.getReferencedNameAsName()?.asString())
                                ?: func.name ?: SpecialNames.ANONYMOUS_STRING
                        append(name)
                    } else {
                        append(name.orErrorName())
                    }
                    if (renderArguments) {
                        append("(")
                        val valueParameters = valueParameters
                        valueParameters.forEachIndexed { index, parameter ->
                            parameter.name?.let { parameterName ->
                                append(parameterName)
                                append(": ")
                                Unit
                            }
                            parameter.typeReference?.text?.let { typeReference ->
                                append(typeReference)
                            }
                            if (index != valueParameters.size - 1) {
                                append(", ")
                            }
                        }
                        append(")")
                    }

                    if (renderReturnType) {
                        typeReference?.text?.let { returnTypeReference ->
                            append(": ")
                            append(returnTypeReference)
                        }
                    }
                }
            }

            fun KtObjectDeclaration.presentableText(): String = buildString {

                if (isCompanion()) {
                    append("companion object")
                } else {
                    append(name.orErrorName())
                }
            }

            return when (declaration) {
                is KtProperty -> declaration.presentableText()
                is KtFunction -> declaration.presentableText()
                is KtObjectDeclaration -> declaration.presentableText()
                is KtScriptInitializer -> {
                    val nameReferenceExpression: KtNameReferenceExpression? =
                        declaration.referenceExpression()

                    val referencedNameAsName = nameReferenceExpression?.getReferencedNameAsName()
                    referencedNameAsName?.asString()?.let { return it }
                    return if (declaration.body is KtExpression) {
                        EXPRESSION
                    } else {
                        CLASS_INITIALIZER
                    }
                }

                is KtAnonymousInitializer -> CLASS_INITIALIZER
                is KtScript -> (declaration.parent as? KtFile)?.name ?: declaration.name.orErrorName()
                else -> declaration.name.orErrorName()
            }
        }

        private fun KtScriptInitializer.referenceExpression(): KtNameReferenceExpression? {
            val body = body
            return when (body) {
                is KtCallExpression -> body.calleeExpression
                is KtExpression -> body.firstChild
                else -> null
            } as? KtNameReferenceExpression
        }

        fun create(project: Project?, ktDeclaration: KtDeclaration, viewSettings: ViewSettings): KtDeclarationTreeNode =
            KtDeclarationTreeNode(project, ktDeclaration, viewSettings)
    }
}
