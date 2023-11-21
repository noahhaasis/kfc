sealed class PopInstruction {
    object RealPop : PopInstruction()
    data class InternalPop(val newOffset: Int) : PopInstruction()
}

class CodeGenerator {
    fun setFunctionCompilationContext(functionCompilationContext: FunctionCompilationContext) {
        this.functionCompilationContext = functionCompilationContext
    }

    fun storeIntoVar(name: String, type: Type) {
        val location = functionCompilationContext!!.environment[name]!!
        when (type) {
            Type.PrimitiveType("i64") -> {
                pop(9, type)
                // store x9 at offset from fp
                when (location) {
                    is RuntimeLocation.Register -> {
                        genAssembly("mov ${location.name}, x9")
                    }
                    is RuntimeLocation.Stack -> {
                        genAssembly("str x9, [x29, #-${location.offset}]")
                    }
                }
            }
            Type.PrimitiveType("bool") -> {
                pop(9, type)
                when (location) {
                    is RuntimeLocation.Register -> {
                        genAssembly("mov ${location.name}, w9")
                    }
                    is RuntimeLocation.Stack -> {
                        genAssembly("strb w9, [x29, #-${location.offset}]")
                    }
                }
            }
            else -> TODO()
        }
    }

    fun pushVariableOntoStack(name: String, type: Type) {
        val location = functionCompilationContext!!.environment[name]!!
        when (type) {
            Type.PrimitiveType("i64") -> {
                when (location) {
                    is RuntimeLocation.Register -> {
                        genAssembly("mov x9, ${location.name} // Load param $name")
                    }
                    is RuntimeLocation.Stack -> {
                        genAssembly("ldr x9, [x29, #-${location.offset}] //  Load var $name")
                    }
                }
                push(9, type)
            }
            Type.PrimitiveType("bool") -> {
                when (location) {
                    is RuntimeLocation.Register -> {
                        genAssembly("mov w9, ${location.name} // Load param $name")
                    }
                    is RuntimeLocation.Stack -> {
                        genAssembly("ldrb w9, [x29, #-${location.offset}] // Load var $name")
                    }
                }
                push(9, type)
            }
            else -> TODO()
        }
    }

    fun genReturn() {
        genAssembly("ldp x29, x30, [sp, #${functionCompilationContext!!.spaceForLocalVars}]")
        genAssembly("add sp, sp, #${functionCompilationContext!!.spaceForLocalVars+16}")
        genAssembly("ret")
    }

    fun genAssemblyMeta(s: String) {
        sb.appendLine(s)
    }

    fun genAssembly(assembly: String) {
        sb.append("        ")
        sb.appendLine(assembly)
    }

    fun generate(): String {
        return sb.toString()
    }

    fun pop(register: Int, type: Type) {
        when (type) {
            Type.PrimitiveType("i64") -> {
                genAssembly("ldr x$register, [sp, #$offsetInteralSpToSp]")
            }
            Type.PrimitiveType("bool") -> {
                genAssembly("ldrb w$register, [sp, #$offsetInteralSpToSp]")
            }
            else -> TODO()
        }

        popIgnore()
    }

    fun popIgnore() {
        val popInstruction = popStack.removeLast()
        offsetInteralSpToSp = when (popInstruction) {
            PopInstruction.RealPop -> {
                genAssembly("add sp, sp, #16")
                0
            }
            is PopInstruction.InternalPop -> {
                popInstruction.newOffset
            }
        }
    }

    fun push(register: Int, type: Type) {
        val numBytes = Type.sizeOfType(type)
        if (offsetInteralSpToSp == 0 || offsetInteralSpToSp < numBytes) {
            popStack.add(PopInstruction.RealPop)
            genAssembly("sub sp, sp, #16")
            offsetInteralSpToSp = 16 - numBytes
        } else {
            popStack.add(PopInstruction.InternalPop(offsetInteralSpToSp))
            offsetInteralSpToSp -= numBytes
            // Padding
            offsetInteralSpToSp -= offsetInteralSpToSp % numBytes
        }

        when (type) {
            Type.PrimitiveType("i64") -> {
                genAssembly("str x$register, [sp, #$offsetInteralSpToSp]")
            }
            Type.PrimitiveType("bool") -> {
                genAssembly("strb w$register, [sp, #$offsetInteralSpToSp]")
            }
            else -> TODO()
        }
    }

    fun saveArgumentsOnTheStack(params: Array<Pair<String, String>>) {
        params.forEachIndexed { i, param ->
            val targetLocationOnStack = functionCompilationContext!!.environment[param.first]
            if (targetLocationOnStack is RuntimeLocation.Stack) {
                when (param.second) {
                    "i64" -> genAssembly("str x${i}, [x29, #-${targetLocationOnStack.offset}]")
                    "bool" -> genAssembly("strb w${i}, [x29, #-${targetLocationOnStack.offset}]")
                    else -> throw TypecheckException("Unknown type ${param.second}")
                }
            } else {
                TODO("Should not occur")
            }
        }
    }

    // TODO: I don't think this system can work with conditionals
    private val popStack: ArrayDeque<PopInstruction> = ArrayDeque()

    // sp + offsetInteralSpToSp points to the top of the stack
    // if offsetInternalSpToSp == 0 and we push then sp = sp-16
    private var offsetInteralSpToSp = 0

    private val sb = StringBuilder()

    private var functionCompilationContext: FunctionCompilationContext? = null
}