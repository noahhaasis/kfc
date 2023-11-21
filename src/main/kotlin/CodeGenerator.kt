class CodeGenerator {
    fun setFunctionCompilationContext(functionCompilationContext: FunctionCompilationContext) {
        this.functionCompilationContext = functionCompilationContext
    }

    fun pop(register: Int, type: Type) {
        when (type) {
            Type.PrimitiveType("i64") -> {
                genAssembly("ldr x$register, [sp]")
                genAssembly("add sp, sp, #16")
            }
            Type.PrimitiveType("bool") -> {
                genAssembly("ldrb w$register, [sp]")
                genAssembly("add sp, sp, #16")
            }
            else -> TODO()
        }
    }

    fun popIgnore(type: Type) {
        genAssembly("add sp, sp, #16")
    }

    fun push(register: Int, type: Type) {
        when (type) {
            Type.PrimitiveType("i64") -> {
                genAssembly("sub sp, sp, #16")
                genAssembly("str x$register, [sp]")
            }
            Type.PrimitiveType("bool") -> {
                genAssembly("sub sp, sp, #16")
                genAssembly("strb w$register, [sp]")
            }
            else -> TODO()
        }

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

    private val sb = StringBuilder()

    private var functionCompilationContext: FunctionCompilationContext? = null
}