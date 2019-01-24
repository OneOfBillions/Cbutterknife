package com.cttrip.bind;

import com.google.auto.service.AutoService;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.ElementKind.METHOD;
import static javax.tools.Diagnostic.Kind.ERROR;


@AutoService(Processor.class)
public class CButterKnifeProcessor extends AbstractProcessor {
    static final String VIEW_TYPE = "android.view.View";
    public static final String SUFFIX = "$$CCViewBinder";

    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;

    private static final List<Class<? extends Annotation>> LISTENERS = Arrays.asList(//
            COnClick.class, //
            COnItemClick.class
    );

    @Override public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        elementUtils = env.getElementUtils();
        typeUtils = env.getTypeUtils();
        filer = env.getFiler();
    }

    @Override public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<String>();
        types.add(CBind.class.getCanonicalName());

        return types;
    }
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<TypeElement, BindingClass> targetClassMap = findAndParseTargets(roundEnv);

        for (Map.Entry<TypeElement, BindingClass> entry : targetClassMap.entrySet()) {
            TypeElement typeElement = entry.getKey();
            BindingClass bindingClass = entry.getValue();

            try {
                JavaFileObject jfo = filer.createSourceFile(bindingClass.getFqcn(), typeElement);
                Writer writer = jfo.openWriter();
                writer.write(bindingClass.brewJava());
                writer.flush();
                writer.close();
            } catch (IOException e) {

            }
        }

        return true;
    }


    private Map<TypeElement, BindingClass> findAndParseTargets(RoundEnvironment env) {
        Map<TypeElement, BindingClass> targetClassMap = new LinkedHashMap<TypeElement, BindingClass>();
        Set<String> erasedTargetNames = new LinkedHashSet<String>();

        // Process each @Bind element.
        for (Element element : env.getElementsAnnotatedWith(CBind.class)) {
            try {
                parseBind(element, targetClassMap, erasedTargetNames);
            } catch (Exception e) {
//                error(element, CBind.class, e);
            }
        }
        // Process each annotation that corresponds to a listener.
        for (Class<? extends Annotation> listener : LISTENERS) {
            findAndParseListener(env, listener, targetClassMap, erasedTargetNames);
        }


        return targetClassMap;
    }

    private void findAndParseListener(RoundEnvironment env,
                                      Class<? extends Annotation> annotationClass, Map<TypeElement, BindingClass> targetClassMap,
                                      Set<String> erasedTargetNames) {
        for (Element element : env.getElementsAnnotatedWith(annotationClass)) {
            try {
                parseListenerAnnotation(annotationClass, element, targetClassMap, erasedTargetNames);
            } catch (Exception e) {
                StringWriter stackTrace = new StringWriter();
                e.printStackTrace(new PrintWriter(stackTrace));

                error(element, "Unable to generate view binder for @%s.\n\n%s",
                        annotationClass.getSimpleName(), stackTrace.toString());
            }
        }
    }


    private void parseListenerAnnotation(Class<? extends Annotation> annotationClass, Element element,
                                         Map<TypeElement, BindingClass> targetClassMap, Set<String> erasedTargetNames)
            throws Exception {
        // This should be guarded by the annotation's @Target but it's worth a check for safe casting.
        if (!(element instanceof ExecutableElement) || element.getKind() != METHOD) {
            throw new IllegalStateException(
                    String.format("@%s annotation must be on a method.", annotationClass.getSimpleName()));
        }

        ExecutableElement executableElement = (ExecutableElement) element;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Assemble information on the method.
        Annotation annotation = element.getAnnotation(annotationClass);
        Method annotationValue = annotationClass.getDeclaredMethod("value");
        if (annotationValue.getReturnType() != String[].class) {
            throw new IllegalStateException(
                    String.format("@%s annotation value() type not int[].", annotationClass));
        }

        String[] ids = (String[]) annotationValue.invoke(annotation);
        String name = executableElement.getSimpleName().toString();
        boolean required = isRequiredBinding(element);



        ListenerClass listener = annotationClass.getAnnotation(ListenerClass.class);


        ListenerMethod method;
        ListenerMethod[] methods = listener.method();
        if (methods.length > 1) {
            throw new IllegalStateException(String.format("Multiple listener methods specified on @%s.",
                    annotationClass.getSimpleName()));
        } else if (methods.length == 1) {
            if (listener.callbacks() != ListenerClass.NONE.class) {
                throw new IllegalStateException(
                        String.format("Both method() and callback() defined on @%s.",
                                annotationClass.getSimpleName()));
            }
            method = methods[0];
        } else {
            Method annotationCallback = annotationClass.getDeclaredMethod("callback");
            Enum<?> callback = (Enum<?>) annotationCallback.invoke(annotation);
            Field callbackField = callback.getDeclaringClass().getField(callback.name());
            method = callbackField.getAnnotation(ListenerMethod.class);
            if (method == null) {
                throw new IllegalStateException(
                        String.format("No @%s defined on @%s's %s.%s.", ListenerMethod.class.getSimpleName(),
                                annotationClass.getSimpleName(), callback.getDeclaringClass().getSimpleName(),
                                callback.name()));
            }
        }

        // Verify that the method has equal to or less than the number of parameters as the listener.
        List<? extends VariableElement> methodParameters = executableElement.getParameters();
        if (methodParameters.size() > method.parameters().length) {
            error(element, "@%s methods can have at most %s parameter(s). (%s.%s)",
                    annotationClass.getSimpleName(), method.parameters().length,
                    enclosingElement.getQualifiedName(), element.getSimpleName());
        }

        // Verify method return type matches the listener.
        TypeMirror returnType = executableElement.getReturnType();
        if (returnType instanceof TypeVariable) {
            TypeVariable typeVariable = (TypeVariable) returnType;
            returnType = typeVariable.getUpperBound();
        }
        if (!returnType.toString().equals(method.returnType())) {
            error(element, "@%s methods must have a '%s' return type. (%s.%s)",
                    annotationClass.getSimpleName(), method.returnType(),
                    enclosingElement.getQualifiedName(), element.getSimpleName());
        }


        Parameter[] parameters = Parameter.NONE;
        if (!methodParameters.isEmpty()) {
            parameters = new Parameter[methodParameters.size()];
            BitSet methodParameterUsed = new BitSet(methodParameters.size());
            String[] parameterTypes = method.parameters();
            for (int i = 0; i < methodParameters.size(); i++) {
                VariableElement methodParameter = methodParameters.get(i);
                TypeMirror methodParameterType = methodParameter.asType();
                if (methodParameterType instanceof TypeVariable) {
                    TypeVariable typeVariable = (TypeVariable) methodParameterType;
                    methodParameterType = typeVariable.getUpperBound();
                }

                for (int j = 0; j < parameterTypes.length; j++) {
                    if (methodParameterUsed.get(j)) {
                        continue;
                    }
                    if (isSubtypeOfType(methodParameterType, parameterTypes[j])
                            || isInterface(methodParameterType)) {
                        parameters[i] = new Parameter(j, methodParameterType.toString());
                        methodParameterUsed.set(j);
                        break;
                    }
                }
                if (parameters[i] == null) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("Unable to match @")
                            .append(annotationClass.getSimpleName())
                            .append(" method arguments. (")
                            .append(enclosingElement.getQualifiedName())
                            .append('.')
                            .append(element.getSimpleName())
                            .append(')');
                    for (int j = 0; j < parameters.length; j++) {
                        Parameter parameter = parameters[j];
                        builder.append("\n\n  Parameter #")
                                .append(j + 1)
                                .append(": ")
                                .append(methodParameters.get(j).asType().toString())
                                .append("\n    ");
                        if (parameter == null) {
                            builder.append("did not match any listener parameters");
                        } else {
                            builder.append("matched listener parameter #")
                                    .append(parameter.getListenerPosition() + 1)
                                    .append(": ")
                                    .append(parameter.getType());
                        }
                    }
                    builder.append("\n\nMethods may have up to ")
                            .append(method.parameters().length)
                            .append(" parameter(s):\n");
                    for (String parameterType : method.parameters()) {
                        builder.append("\n  ").append(parameterType);
                    }
                    builder.append(
                            "\n\nThese may be listed in any order but will be searched for from top to bottom.");
                    error(executableElement, builder.toString());
                    return;
                }
            }
        }

        MethodViewBinding binding = new MethodViewBinding(name, Arrays.asList(parameters), required);
        BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
        for (String id : ids) {
            if (!bindingClass.addMethod(id, listener, method, binding)) {
                error(element, "Multiple listener methods with return value specified for ID %d. (%s.%s)",
                        id, enclosingElement.getQualifiedName(), element.getSimpleName());
                return;
            }
        }

        // Add the type-erased version to the valid binding targets set.
        erasedTargetNames.add(enclosingElement.toString());
    }
    private void parseBind(Element element, Map<TypeElement, BindingClass> targetClassMap,
                           Set<String> erasedTargetNames) {
        // Verify common generated code restrictions.


            parseBindOne(element, targetClassMap, erasedTargetNames);
    }

    private void parseBindOne(Element element, Map<TypeElement, BindingClass> targetClassMap,
                              Set<String> erasedTargetNames) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        // Verify that the target type extends from View.
        TypeMirror elementType = element.asType();
        if (elementType.getKind() == TypeKind.TYPEVAR) {
            TypeVariable typeVariable = (TypeVariable) elementType;
            elementType = typeVariable.getUpperBound();
        }

        // Assemble information on the field.
        String ids = element.getAnnotation(CBind.class).value();


        String id = ids;
        BindingClass bindingClass = targetClassMap.get(enclosingElement);
        if (bindingClass != null) {
            ViewBindings viewBindings = bindingClass.getViewBinding(id);
            if (viewBindings != null) {
                Iterator<FieldViewBinding> iterator = viewBindings.getFieldBindings().iterator();
                if (iterator.hasNext()) {
                    FieldViewBinding existingBinding = iterator.next();
                    error(element, "Attempt to use @%s for an already bound ID %d on '%s'. (%s.%s)",
                            CBind.class.getSimpleName(), id, existingBinding.getName(),
                            enclosingElement.getQualifiedName(), element.getSimpleName());
                    return;
                }
            }
        } else {
            bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
        }

        String name = element.getSimpleName().toString();
        String type = elementType.toString();
        boolean required = isRequiredBinding(element);

        FieldViewBinding binding = new FieldViewBinding(name, type, required);
        bindingClass.addField(id, binding);

        // Add the type-erased version to the valid binding targets set.
        erasedTargetNames.add(enclosingElement.toString());
    }
    private static boolean isRequiredBinding(Element element) {
        return !hasAnnotationWithName(element, NULLABLE_ANNOTATION_NAME);
    }

    private static boolean hasAnnotationWithName(Element element, String simpleName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
            if (simpleName.equals(annotationName)) {
                return true;
            }
        }
        return false;
    }
    private static final String NULLABLE_ANNOTATION_NAME = "Nullable";

    private BindingClass getOrCreateTargetClass(Map<TypeElement, BindingClass> targetClassMap,
                                                TypeElement enclosingElement) {
        BindingClass bindingClass = targetClassMap.get(enclosingElement);
        if (bindingClass == null) {
            String targetType = enclosingElement.getQualifiedName().toString();
            String classPackage = getPackageName(enclosingElement);
            String className = getClassName(enclosingElement, classPackage) + SUFFIX;

            bindingClass = new BindingClass(classPackage, className, targetType);
            targetClassMap.put(enclosingElement, bindingClass);
        }
        return bindingClass;
    }
    private static String getClassName(TypeElement type, String packageName) {
        int packageLen = packageName.length() + 1;
        return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
    }

    private boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
        if (otherType.equals(typeMirror.toString())) {
            return true;
        }
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() > 0) {
            StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
            typeString.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    typeString.append(',');
                }
                typeString.append('?');
            }
            typeString.append('>');
            if (typeString.toString().equals(otherType)) {
                return true;
            }
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        TypeMirror superType = typeElement.getSuperclass();
        if (isSubtypeOfType(superType, otherType)) {
            return true;
        }
        for (TypeMirror interfaceType : typeElement.getInterfaces()) {
            if (isSubtypeOfType(interfaceType, otherType)) {
                return true;
            }
        }
        return false;
    }
    private String getPackageName(TypeElement type) {
        return elementUtils.getPackageOf(type).getQualifiedName().toString();
    }
    private void error(Element element, String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(ERROR, message, element);
    }

    private boolean isInterface(TypeMirror typeMirror) {
        if (!(typeMirror instanceof DeclaredType)) {
            return false;
        }
        return ((DeclaredType) typeMirror).asElement().getKind() == INTERFACE;
    }
}
