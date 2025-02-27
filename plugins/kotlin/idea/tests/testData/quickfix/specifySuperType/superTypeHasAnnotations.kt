// "Specify supertype" "true"
interface Z {
    fun foo() {}
}

open class X {
    open fun foo() {}
}

class Test : (@Suppress("foo") X)(), Z {
    override fun foo() {
        <caret>super.foo()
    }
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.SpecifySuperTypeFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.SpecifySuperTypeFixFactory$applicator$1