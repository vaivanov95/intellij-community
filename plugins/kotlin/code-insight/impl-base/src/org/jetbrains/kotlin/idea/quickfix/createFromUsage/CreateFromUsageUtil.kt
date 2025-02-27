// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.quickfix.createFromUsage

import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.isAncestor
import org.jetbrains.kotlin.idea.base.psi.isMultiLine
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import kotlin.math.max

object CreateFromUsageUtil {
    // TODO: Simplify and use formatter as much as possible
    @Suppress("UNCHECKED_CAST")
    fun <D : KtNamedDeclaration> placeDeclarationInContainer(
      declaration: D,
      container: PsiElement,
      anchor: PsiElement,
      fileToEdit: KtFile = container.containingFile as KtFile
    ): D {
        val psiFactory = KtPsiFactory(container.project)
        val newLine = psiFactory.createNewLine()

        fun calcNecessaryEmptyLines(decl: KtDeclaration, after: Boolean): Int {
            var lineBreaksPresent = 0
            var neighbor: PsiElement? = null

            siblingsLoop@
            for (sibling in decl.siblings(forward = after, withItself = false)) {
                when (sibling) {
                    is PsiWhiteSpace -> lineBreaksPresent += (sibling.text ?: "").count { it == '\n' }
                    else -> {
                        neighbor = sibling
                        break@siblingsLoop
                    }
                }
            }

            val neighborType = neighbor?.node?.elementType
            val lineBreaksNeeded = when {
              neighborType == KtTokens.LBRACE || neighborType == KtTokens.RBRACE -> 1
              neighbor is KtDeclaration && (neighbor !is KtProperty || decl !is KtProperty) -> 2
                else -> 1
            }

            return max(lineBreaksNeeded - lineBreaksPresent, 0)
        }

        val actualContainer = (container as? KtClassOrObject)?.getOrCreateBody() ?: container

        fun addDeclarationToClassOrObject(
          classOrObject: KtClassOrObject,
          declaration: KtNamedDeclaration
        ): KtNamedDeclaration {
            val classBody = classOrObject.getOrCreateBody()
            return if (declaration is KtNamedFunction) {
                val neighbor = PsiTreeUtil.skipSiblingsBackward(
                  classBody.rBrace ?: classBody.lastChild!!,
                  PsiWhiteSpace::class.java
                )
                classBody.addAfter(declaration, neighbor) as KtNamedDeclaration
            } else classBody.addAfter(declaration, classBody.lBrace!!) as KtNamedDeclaration
        }


        fun addNextToOriginalElementContainer(addBefore: Boolean): D {
            val sibling = anchor.parentsWithSelf.first { it.parent == actualContainer }
            return if (addBefore || PsiTreeUtil.hasErrorElements(sibling)) {
                actualContainer.addBefore(declaration, sibling)
            } else {
                actualContainer.addAfter(declaration, sibling)
            } as D
        }

        val declarationInPlace = when {
            declaration is KtPrimaryConstructor -> {
              (container as KtClass).createPrimaryConstructorIfAbsent().replaced(declaration)
            }

          declaration is KtProperty && container !is KtBlockExpression -> {
                val sibling = actualContainer.getChildOfType<KtProperty>() ?: when (actualContainer) {
                    is KtClassBody -> actualContainer.declarations.firstOrNull() ?: actualContainer.rBrace
                    is KtFile -> actualContainer.declarations.first()
                    else -> null
                }
                sibling?.let { actualContainer.addBefore(declaration, it) as D } ?: fileToEdit.add(declaration) as D
            }

            actualContainer.isAncestor(anchor, true) -> {
                val insertToBlock = container is KtBlockExpression
                if (insertToBlock) {
                    val parent = container.parent
                    if (parent is KtFunctionLiteral) {
                        if (!parent.isMultiLine()) {
                            parent.addBefore(newLine, container)
                            parent.addAfter(newLine, container)
                        }
                    }
                }
                addNextToOriginalElementContainer(insertToBlock || declaration is KtTypeAlias)
            }

            container is KtFile -> container.add(declaration) as D

            container is PsiClass -> {
                if (declaration is KtSecondaryConstructor) {
                    val wrappingClass = psiFactory.createClass("class ${container.name} {\n}")
                    addDeclarationToClassOrObject(wrappingClass, declaration)
                    (fileToEdit.add(wrappingClass) as KtClass).declarations.first() as D
                } else {
                    fileToEdit.add(declaration) as D
                }
            }

            container is KtClassOrObject -> {
                var sibling: PsiElement? = container.declarations.lastOrNull { it::class == declaration::class }
                if (sibling == null && declaration is KtProperty) {
                    sibling = container.body?.lBrace
                }

              org.jetbrains.kotlin.idea.core.insertMembersAfterAndReformat(null, container, declaration, sibling)
            }
            else -> throw KotlinExceptionWithAttachments("Invalid containing element: ${container::class.java}")
                .withPsiAttachment("container", container)
        }

        when (declaration) {
            is KtEnumEntry -> {
                val prevEnumEntry = declarationInPlace.siblings(forward = false, withItself = false).firstIsInstanceOrNull<KtEnumEntry>()
                if (prevEnumEntry != null) {
                    if ((prevEnumEntry.prevSibling as? PsiWhiteSpace)?.text?.contains('\n') == true) {
                        declarationInPlace.parent.addBefore(psiFactory.createNewLine(), declarationInPlace)
                    }
                    val comma = psiFactory.createComma()
                    if (prevEnumEntry.allChildren.any { it.node.elementType == KtTokens.COMMA }) {
                        declarationInPlace.add(comma)
                    } else {
                        prevEnumEntry.add(comma)
                    }
                    val semicolon = prevEnumEntry.allChildren.firstOrNull { it.node?.elementType == KtTokens.SEMICOLON }
                    if (semicolon != null) {
                      (semicolon.prevSibling as? PsiWhiteSpace)?.text?.let {
                            declarationInPlace.add(psiFactory.createWhiteSpace(it))
                        }
                        declarationInPlace.add(psiFactory.createSemicolon())
                        semicolon.delete()
                    }
                }
            }
            !is KtPrimaryConstructor -> {
                val parent = declarationInPlace.parent
                calcNecessaryEmptyLines(declarationInPlace, false).let {
                    if (it > 0) parent.addBefore(psiFactory.createNewLine(it), declarationInPlace)
                }
                calcNecessaryEmptyLines(declarationInPlace, true).let {
                    if (it > 0) parent.addAfter(psiFactory.createNewLine(it), declarationInPlace)
                }
            }
        }
        return declarationInPlace
    }

    fun computeVisibilityModifier(expression: KtCallExpression): KtModifierKeywordToken? {
        val parentFunction = expression.getStrictParentOfType<KtNamedFunction>()
        return if (parentFunction?.hasModifier(KtTokens.INLINE_KEYWORD) == true) {
            when {
                parentFunction.isPublic -> (KtTokens.PUBLIC_KEYWORD)
                parentFunction.isProtected() -> (KtTokens.PROTECTED_KEYWORD)
                else -> null
            }
        } else {
            null
        }
    }

    fun computeDefaultVisibilityAsString(
      containingElement: PsiElement,
      isAbstract: Boolean,
      isExtension: Boolean,
      isConstructor: Boolean,
      originalElement: PsiElement
    ): String {
        val modifier = if (isAbstract) null
        else if (containingElement is KtClassOrObject
            && !(containingElement is KtClass && containingElement.isInterface())
            && containingElement.isAncestor(originalElement)
            && !isConstructor
        ) JvmModifier.PRIVATE
        else if (isExtension) {
            if (containingElement is KtFile && containingElement.isScript()) null else JvmModifier.PRIVATE
        } else null
        return modifierToString(modifier)
    }

    private val modifierToKotlinToken: Map<JvmModifier, KtModifierKeywordToken> = mapOf(
      JvmModifier.PRIVATE to KtTokens.PRIVATE_KEYWORD,
      JvmModifier.PACKAGE_LOCAL to KtTokens.INTERNAL_KEYWORD,
      JvmModifier.PROTECTED to KtTokens.PROTECTED_KEYWORD,
      JvmModifier.PUBLIC to KtTokens.PUBLIC_KEYWORD
    )
    fun modifierToString(modifier: JvmModifier?):String {
        return modifierToKotlinToken[modifier]?.let { if (it == KtTokens.PUBLIC_KEYWORD) "" else it.value } ?: ""
    }
}