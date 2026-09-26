import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.kr328.simplefcmfix"

    enableKotlin = false

    defaultConfig {
        applicationId = "com.github.kr328.simplefcmfix"
        versionCode = 10013
        versionName = "1.13"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes.add("kotlin/**")
        }
    }
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}

abstract class RefineTransformer : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val className = classContext.currentClassData.className

        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val nextMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)

                return object : MethodNode(
                    Opcodes.ASM9,
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions,
                ) {
                    override fun visitEnd() {
                        rewriteMethod(className, this)
                        accept(nextMethodVisitor)
                    }
                }
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.classAnnotations.contains("com.github.kr328.simplefcmfix.refine.Refine")
    }

    private fun rewriteMethod(className: String, method: MethodNode) {
        val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
        val refinements = annotations.filter { it.desc in refinementAnnotations }

        if (refinements.isEmpty()) {
            return
        }
        if (refinements.size != 1) {
            fail(className, method, "must have exactly one Refine method annotation")
        }
        if (method.name == "<init>" || method.name == "<clinit>") {
            fail(className, method, "constructors cannot be refined")
        }
        if (method.access and Opcodes.ACC_STATIC == 0) {
            fail(className, method, "refined bridge methods must be static")
        }
        if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) {
            fail(className, method, "refined bridge methods must have a method body")
        }

        val annotation = refinements.single()
        val argumentTypes = Type.getArgumentTypes(method.desc)
        val returnType = Type.getReturnType(method.desc)

        clearMethodBody(method)
        method.visitCode()

        when (annotation.desc) {
            invokeVirtualAnnotation -> {
                if (argumentTypes.isEmpty()) {
                    fail(className, method, "InvokeVirtual requires a receiver as its first parameter")
                }

                val targetName = annotation.targetName(method)
                val receiverType = argumentTypes.first()
                requireReferenceType(className, method, receiverType, "InvokeVirtual receiver")
                val annotatedOwner = annotation.typeValue("value")
                val owner = if (annotatedOwner == null || annotatedOwner == Type.VOID_TYPE) {
                    receiverType
                } else {
                    annotatedOwner.also {
                        requireReferenceType(className, method, it, "InvokeVirtual owner")
                    }
                }

                loadArguments(method, argumentTypes)
                val targetDescriptor = Type.getMethodDescriptor(
                    returnType,
                    *argumentTypes.copyOfRange(1, argumentTypes.size),
                )
                method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    owner.internalName,
                    targetName,
                    targetDescriptor,
                    false,
                )
            }

            invokeStaticAnnotation -> {
                val targetName = annotation.targetName(method)
                val owner = annotation.requiredOwner(className, method)
                loadArguments(method, argumentTypes)
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    owner.internalName,
                    targetName,
                    method.desc,
                    false,
                )
            }

            getStaticAnnotation -> {
                val owner = annotation.requiredOwner(className, method)
                if (argumentTypes.isNotEmpty()) {
                    fail(className, method, "GetStatic bridge methods cannot have parameters")
                }
                if (returnType == Type.VOID_TYPE) {
                    fail(className, method, "GetStatic bridge methods must return a value")
                }
                val fieldName = annotation.value("name") as? String
                if (fieldName.isNullOrEmpty()) {
                    fail(className, method, "GetStatic requires a non-empty field name")
                }
                method.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    owner.internalName,
                    fieldName,
                    returnType.descriptor,
                )
            }
        }

        method.visitInsn(returnType.getOpcode(Opcodes.IRETURN))
        method.visitMaxs(0, 0)
    }

    private fun clearMethodBody(method: MethodNode) {
        method.instructions.clear()
        method.tryCatchBlocks.clear()
        method.localVariables?.clear()
        method.visibleLocalVariableAnnotations?.clear()
        method.invisibleLocalVariableAnnotations?.clear()
        method.maxStack = 0
        method.maxLocals = 0
    }

    private fun loadArguments(method: MethodNode, argumentTypes: Array<Type>) {
        var localIndex = 0
        for (argumentType in argumentTypes) {
            method.visitVarInsn(argumentType.getOpcode(Opcodes.ILOAD), localIndex)
            localIndex += argumentType.size
        }
    }

    private fun AnnotationNode.requiredOwner(className: String, method: MethodNode): Type {
        val owner = typeValue("value")
            ?: fail(className, method, "${annotationName(desc)} requires an owner class")
        if (owner.sort != Type.OBJECT) {
            fail(className, method, "${annotationName(desc)} owner must be a class type")
        }
        return owner
    }

    private fun AnnotationNode.typeValue(name: String): Type? = value(name) as? Type

    private fun AnnotationNode.targetName(method: MethodNode): String {
        val annotatedName = value("name") as? String
        return annotatedName?.takeIf { it.isNotEmpty() } ?: method.name
    }

    private fun AnnotationNode.value(name: String): Any? {
        val annotationValues = values ?: return null
        for (index in annotationValues.indices step 2) {
            if (annotationValues[index] == name) {
                return annotationValues[index + 1]
            }
        }
        return null
    }

    private fun requireReferenceType(
        className: String,
        method: MethodNode,
        type: Type,
        subject: String,
    ) {
        if (type.sort != Type.OBJECT && type.sort != Type.ARRAY) {
            fail(className, method, "$subject must be a reference type")
        }
    }

    private fun annotationName(descriptor: String): String = when (descriptor) {
        invokeVirtualAnnotation -> "InvokeVirtual"
        invokeStaticAnnotation -> "InvokeStatic"
        getStaticAnnotation -> "GetStatic"
        else -> descriptor
    }

    private fun fail(className: String, method: MethodNode, message: String): Nothing {
        throw IllegalArgumentException("RefineTransformer: $className.${method.name}${method.desc}: $message")
    }

    companion object {
        private const val invokeVirtualAnnotation =
            "Lcom/github/kr328/simplefcmfix/refine/Refine\$InvokeVirtual;"
        private const val invokeStaticAnnotation =
            "Lcom/github/kr328/simplefcmfix/refine/Refine\$InvokeStatic;"
        private const val getStaticAnnotation =
            "Lcom/github/kr328/simplefcmfix/refine/Refine\$GetStatic;"
        private val refinementAnnotations = setOf(
            invokeVirtualAnnotation,
            invokeStaticAnnotation,
            getStaticAnnotation,
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            RefineTransformer::class.java,
            InstrumentationScope.PROJECT,
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }
}
