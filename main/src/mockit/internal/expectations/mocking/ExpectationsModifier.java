/*
 * Copyright (c) 2006-2014 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.mocking;

import java.util.*;
import static java.lang.reflect.Modifier.*;

import mockit.external.asm4.*;
import mockit.internal.util.*;
import static mockit.external.asm4.Opcodes.*;

import org.jetbrains.annotations.*;

final class ExpectationsModifier extends MockedTypeModifier
{
   private static final int METHOD_ACCESS_MASK = ACC_SYNTHETIC + ACC_ABSTRACT;
   private static final int PRIVATE_NATIVE_METHOD = ACC_PRIVATE + ACC_NATIVE;
   private static final int PRIVATE_STATIC_METHOD = ACC_PRIVATE + ACC_STATIC;

   private static final Map<String, String> DEFAULT_FILTERS = new HashMap<String, String>();
   static {
      DEFAULT_FILTERS.put("java/lang/Object", "<init> clone getClass hashCode");
      DEFAULT_FILTERS.put("java/lang/String", "");
      DEFAULT_FILTERS.put("java/lang/AbstractStringBuilder", "");
      DEFAULT_FILTERS.put("java/lang/StringBuilder", "");
      DEFAULT_FILTERS.put("java/lang/StringBuffer", "");
      DEFAULT_FILTERS.put("java/lang/System",
                          "arraycopy getProperties getSecurityManager identityHashCode mapLibraryName");
      DEFAULT_FILTERS.put("java/lang/Throwable", "<init> fillInStackTrace");
      DEFAULT_FILTERS.put("java/lang/Exception", "<init>");
      DEFAULT_FILTERS.put("java/lang/Thread", "currentThread getName interrupted isInterrupted");
      DEFAULT_FILTERS.put("java/util/AbstractCollection", "<init>");
      DEFAULT_FILTERS.put("java/util/AbstractSet", "<init>");
      DEFAULT_FILTERS.put("java/util/HashSet", "<init> add");
      DEFAULT_FILTERS.put("java/util/Hashtable", "<init> containsKey get hash");
      DEFAULT_FILTERS.put("java/util/Properties", "<init>");
      DEFAULT_FILTERS.put("java/util/ArrayList", "");
      DEFAULT_FILTERS.put("java/util/HashMap", "");
      DEFAULT_FILTERS.put("java/util/jar/JarEntry", "<init>");
      DEFAULT_FILTERS.put("java/util/logging/Logger", "<init> getName");
   }

   @Nullable private final MockingConfiguration mockingCfg;
   private String className;
   @Nullable private String baseClassNameForCapturedInstanceMethods;
   private boolean stubOutClassInitialization;
   private boolean ignoreConstructors;
   private int executionMode;
   private boolean isProxy;
   @Nullable private String defaultFilters;
   @Nullable List<String> enumSubclasses;

   ExpectationsModifier(
      @Nullable ClassLoader classLoader, @NotNull ClassReader classReader, @Nullable MockedType typeMetadata)
   {
      super(classReader);

      setUseMockingBridge(classLoader);
      executionMode = 3;

      if (typeMetadata == null) {
         mockingCfg = null;
      }
      else {
         mockingCfg = typeMetadata.mockingCfg;
         stubOutClassInitialization = typeMetadata.isClassInitializationToBeStubbedOut();
         useInstanceBasedMockingIfApplicable(typeMetadata);
      }
   }

   private void useInstanceBasedMockingIfApplicable(@NotNull MockedType typeMetadata)
   {
      if (typeMetadata.injectable) {
         ignoreConstructors = typeMetadata.getMaxInstancesToCapture() <= 0;
         executionMode = 2;
      }
   }

   public void setClassNameForCapturedInstanceMethods(@NotNull String internalClassName)
   {
      baseClassNameForCapturedInstanceMethods = internalClassName;
   }

   public void useDynamicMocking(boolean methodsOnly)
   {
      ignoreConstructors = methodsOnly;
      executionMode = 1;
   }

   @Override
   public void visit(
      int version, int access, @NotNull String name, @Nullable String signature, @Nullable String superName,
      @Nullable String[] interfaces)
   {
      if ("java/lang/Class".equals(name) || "java/lang/ClassLoader".equals(name) ) {
         throw new IllegalArgumentException("Class " + name.replace('/', '.') + " is not mockable");
      }

      super.visit(version, access, name, signature, superName, interfaces);
      isProxy = "java/lang/reflect/Proxy".equals(superName);

      if (isProxy) {
         assert interfaces != null;
         className = interfaces[0];
         defaultFilters = null;
      }
      else {
         className = name;
         defaultFilters = DEFAULT_FILTERS.get(name);

         if (defaultFilters != null && defaultFilters.isEmpty()) {
            throw VisitInterruptedException.INSTANCE;
         }
      }
   }

   @Override
   public void visitInnerClass(@NotNull String name, @Nullable String outerName, @Nullable String innerName, int access)
   {
      cw.visitInnerClass(name, outerName, innerName, access);

      if (access == ACC_ENUM + ACC_STATIC) {
         if (enumSubclasses == null) {
            enumSubclasses = new ArrayList<String>();
         }

         enumSubclasses.add(name);
      }
   }

   @Override
   @Nullable public MethodVisitor visitMethod(
      int access, @NotNull String name, @NotNull String desc, @Nullable String signature, @Nullable String[] exceptions)
   {
      boolean syntheticOrAbstractMethod = (access & METHOD_ACCESS_MASK) != 0;

      if (syntheticOrAbstractMethod || isProxy && isConstructorOrSystemMethodNotToBeMocked(name, desc)) {
         return unmodifiedBytecode(access, name, desc, signature, exceptions);
      }

      boolean noFiltersToMatch = mockingCfg == null;
      boolean matchesFilters = noFiltersToMatch || mockingCfg.matchesFilters(name, desc);

      if ("<clinit>".equals(name)) {
         return stubOutClassInitializationIfApplicable(access, noFiltersToMatch, matchesFilters);
      }

      if (stubOutFinalizeMethod(access, name, desc)) {
         return null;
      }

      if (
         !matchesFilters ||
         isMethodFromCapturedClassNotToBeMocked(access) ||
         noFiltersToMatch && isMethodOrConstructorNotToBeMocked(access, name)
      ) {
         return unmodifiedBytecode(access, name, desc, signature, exceptions);
      }

      // Otherwise, replace original implementation with redirect to JMockit.
      startModifiedMethodVersion(access, name, desc, signature, exceptions);

      boolean visitingConstructor = "<init>".equals(name);

      if (visitingConstructor && superClassName != null) {
         generateCallToSuperConstructor();
      }

      String internalClassName = className;

      if (!visitingConstructor && baseClassNameForCapturedInstanceMethods != null) {
         internalClassName = baseClassNameForCapturedInstanceMethods;
      }

      int actualExecutionMode = determineAppropriateExecutionMode(visitingConstructor);

      if (useMockingBridge) {
         return generateCallToHandlerThroughMockingBridge(signature, internalClassName, actualExecutionMode);
      }

      generateDirectCallToHandler(internalClassName, access, name, desc, signature, actualExecutionMode);
      generateDecisionBetweenReturningOrContinuingToRealImplementation();

      // Constructors of non-JRE classes can't be modified (unless running with "-noverify") in a way that
      // "super(...)/this(...)" get called twice, so we disregard such calls when copying the original bytecode.
      return visitingConstructor ? new DynamicConstructorModifier() : copyOriginalImplementationCode();
   }

   @Nullable
   private MethodVisitor unmodifiedBytecode(
      int access, @NotNull String name, @NotNull String desc, @Nullable String signature, @Nullable String[] exceptions)
   {
      return cw.visitMethod(access, name, desc, signature, exceptions);
   }

   private static boolean isConstructorOrSystemMethodNotToBeMocked(@NotNull String name, @NotNull String desc)
   {
      return
         "<init>".equals(name) || ObjectMethods.isMethodFromObject(name, desc) ||
         "annotationType".equals(name) && "()Ljava/lang/Class;".equals(desc);
   }

   @Nullable
   private MethodVisitor stubOutClassInitializationIfApplicable(int access, boolean noFilters, boolean matchesFilters)
   {
      mw = cw.visitMethod(access, "<clinit>", "()V", null, null);

      if (!noFilters && matchesFilters || stubOutClassInitialization) {
         generateEmptyImplementation();
         return null;
      }

      return mw;
   }

   private boolean stubOutFinalizeMethod(int access, @NotNull String name, @NotNull String desc)
   {
      if ("finalize".equals(name) && "()V".equals(desc)) {
         mw = cw.visitMethod(access, name, desc, null, null);
         generateEmptyImplementation();
         return true;
      }
      
      return false;
   }
   
   private boolean isMethodFromCapturedClassNotToBeMocked(int access)
   {
      return baseClassNameForCapturedInstanceMethods != null && (access & PRIVATE_STATIC_METHOD) != 0;
   }

   private boolean isMethodOrConstructorNotToBeMocked(int access, @NotNull String name)
   {
      return
         isConstructorToBeIgnored(name) ||
         isStaticMethodToBeIgnored(access) ||
         isNativeMethodForDynamicMocking(access) ||
         access == ACC_NATIVE || (access & PRIVATE_NATIVE_METHOD) == PRIVATE_NATIVE_METHOD ||
         defaultFilters != null && defaultFilters.contains(name);
   }

   private boolean isConstructorToBeIgnored(@NotNull String name)
   {
      return ignoreConstructors && "<init>".equals(name);
   }

   private boolean isStaticMethodToBeIgnored(int access) { return executionMode == 2 && isStatic(access); }
   private boolean isNativeMethodForDynamicMocking(int access) { return executionMode < 3 && isNative(access); }

   private int determineAppropriateExecutionMode(boolean visitingConstructor)
   {
      if (executionMode == 2) {
         if (visitingConstructor) {
            return ignoreConstructors ? 3 : 1;
         }
         else if (isStatic(methodAccess)) {
            return 3;
         }
      }

      return executionMode;
   }

   @NotNull
   private MethodVisitor generateCallToHandlerThroughMockingBridge(
      @Nullable String genericSignature, @NotNull String internalClassName, int actualExecutionMode)
   {
      generateCodeToObtainInstanceOfMockingBridge(MockedBridge.MB);

      // First and second "invoke" arguments:
      boolean isStatic = generateCodeToPassThisOrNullIfStaticMethod(methodAccess);
      mw.visitInsn(ACONST_NULL);

      // Create array for call arguments (third "invoke" argument):
      Type[] argTypes = Type.getArgumentTypes(methodDesc);
      generateCodeToCreateArrayOfObject(6 + argTypes.length);

      int i = 0;
      generateCodeToFillArrayElement(i++, methodAccess);
      generateCodeToFillArrayElement(i++, internalClassName);
      generateCodeToFillArrayElement(i++, methodName);
      generateCodeToFillArrayElement(i++, methodDesc);
      generateCodeToFillArrayElement(i++, genericSignature);
      generateCodeToFillArrayElement(i++, actualExecutionMode);

      generateCodeToFillArrayWithParameterValues(argTypes, i, isStatic ? 0 : 1);
      generateCallToInvocationHandler();

      generateDecisionBetweenReturningOrContinuingToRealImplementation();

      // Copies the entire original implementation even for a constructor, in which case the complete bytecode inside
      // the constructor fails the strict verification activated by "-Xfuture". However, this is necessary to allow the
      // full execution of a JRE constructor when the call was not meant to be mocked.
      return copyOriginalImplementationCode();
   }

   private void generateDecisionBetweenReturningOrContinuingToRealImplementation()
   {
      Label startOfRealImplementation = new Label();
      mw.visitInsn(DUP);
      mw.visitLdcInsn(VOID_TYPE);
      mw.visitJumpInsn(IF_ACMPEQ, startOfRealImplementation);
      generateReturnWithObjectAtTopOfTheStack(methodDesc);
      mw.visitLabel(startOfRealImplementation);
      mw.visitInsn(POP);
   }

   @NotNull private MethodVisitor copyOriginalImplementationCode()
   {
      if (isNative(methodAccess)) {
         generateEmptyImplementation(methodDesc);
         return methodAnnotationsVisitor;
      }

      return new DynamicModifier();
   }

   private class DynamicModifier extends MethodVisitor
   {
      DynamicModifier() { super(mw); }

      @Override
      public final void visitLocalVariable(
         @NotNull String name, @NotNull String desc, @Nullable String signature,
         @NotNull Label start, @NotNull Label end, int index)
      {
         registerParameterName(name, index);

         // For some reason, the start position for "this" gets displaced by bytecode inserted at the beginning,
         // in a method modified by the EMMA tool. If not treated, this causes a ClassFormatError.
         if (end.position > 0 && start.position > end.position) {
            start.position = end.position;
         }

         // Ignores any local variable with required information missing, to avoid a VerifyError/ClassFormatError.
         if (start.position > 0 && end.position > 0) {
            mw.visitLocalVariable(name, desc, signature, start, end, index);
         }
      }
   }

   private final class DynamicConstructorModifier extends DynamicModifier
   {
      @Override
      public void visitMethodInsn(int opcode, @NotNull String owner, @NotNull String name, @NotNull String desc)
      {
         disregardIfInvokingAnotherConstructor(opcode, owner, name, desc);
      }
   }
}