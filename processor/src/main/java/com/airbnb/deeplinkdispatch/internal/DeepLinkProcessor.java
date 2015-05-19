package com.airbnb.deeplinkdispatch.internal;

import com.google.auto.service.AutoService;

import com.airbnb.deeplinkdispatch.DeepLink;
import com.airbnb.deeplinkdispatch.DeepLinkEntry;
import com.airbnb.deeplinkdispatch.javawriter.JavaWriter;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

@AutoService(Processor.class)
public class DeepLinkProcessor extends AbstractProcessor {

  private Filer filer;
  private Messager messager;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> annotations = new LinkedHashSet<String>();
    annotations.add(DeepLink.class.getCanonicalName());
    return annotations;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    List<DeepLinkAnnotatedElement> deepLinkElements = new ArrayList<>();

    for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(DeepLink.class)) {
      ElementKind kind = annotatedElement.getKind();
      if (kind != ElementKind.METHOD && kind != ElementKind.CLASS) {
        error(annotatedElement, "Only classes and methods can be annotated with @%s", DeepLink.class.getSimpleName());
      }

      DeepLinkEntry.Type type = kind == ElementKind.CLASS ? DeepLinkEntry.Type.CLASS : DeepLinkEntry.Type.METHOD;
      DeepLinkAnnotatedElement element = new DeepLinkAnnotatedElement(annotatedElement, type);
      deepLinkElements.add(element);
    }

    if (deepLinkElements.size() > 0) {
      try {
        generateRegistry(deepLinkElements);
        generateDeepLinkActivity();
      } catch (IOException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, "Error creating file");
      }
    }

    return true;
  }

  private void error(Element e, String msg, Object... args) {
    messager.printMessage(
        Diagnostic.Kind.ERROR,
        String.format(msg, args),
        e);
  }

  private void generateRegistry(List<DeepLinkAnnotatedElement> elements) throws IOException {
    JavaFileObject jfo = filer.createSourceFile("DeepLinkLoader");
    Writer writer = jfo.openWriter();
    JavaWriter jw = new JavaWriter(writer);

    jw.emitPackage("com.airbnb.deeplinkdispatch");

    jw.emitImports("com.airbnb.deeplinkdispatch.DeepLinkEntry.Type");
    jw.emitEmptyLine();

    jw.beginType("DeepLinkLoader", "class", EnumSet.of(Modifier.PUBLIC), null, "Loader");
    jw.emitEmptyLine();

    jw.beginConstructor(EnumSet.of(Modifier.PUBLIC));
    jw.endConstructor();
    jw.emitEmptyLine();

    jw.beginMethod("void", "load", EnumSet.of(Modifier.PUBLIC), "DeepLinkRegistry",
                   "registry");
    for (DeepLinkAnnotatedElement element: elements) {
      String hostPath;
      if (element.getPath().isEmpty()) {
        hostPath = "\"" + element.getHost() + "\"";
      } else {
        hostPath = "\"" + element.getHost() + "/" + element.getPath() + "\"";
      }
      String type = "Type." + element.getAnnotationType().toString();
      String activity = "\"" + element.getActivity() + "\"";
      String method = element.getMethod() == null ? "null" : "\"" + element.getMethod() + "\"";
      jw.emitStatement(String.format("registry.registerDeepLink(%s, %s, %s, %s)", hostPath, type, activity, method));
    }
    jw.endMethod();

    jw.endType();

    jw.close();
  }

  private void generateDeepLinkActivity() throws IOException {
    JavaFileObject jfo = filer.createSourceFile("DeepLinkActivity");

    Writer writer = jfo.openWriter();
    JavaWriter jw = new JavaWriter(writer);
    jw.emitPackage("com.airbnb.deeplinkdispatch");

    List<String> imports = Arrays.asList("android.app.Activity",
                                         "android.content.Context",
                                         "android.content.Intent",
                                         "android.net.Uri",
                                         "android.os.Bundle",
                                         "android.util.Log",
                                         "android.util.Log",
                                         "java.lang.reflect.InvocationTargetException",
                                         "java.lang.reflect.Method",
                                         "java.util.Map");
    jw.emitImports(imports);
    jw.emitEmptyLine();


    jw.beginType("DeepLinkActivity", "class", EnumSet.of(Modifier.PUBLIC), "Activity");
    jw.emitEmptyLine();

    jw.emitField("String",
                 "TAG",
                 EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL),
                 "DeepLinkActivity.class.getSimpleName()");
    jw.emitEmptyLine();

    jw.emitAnnotation(Override.class);
    jw.beginMethod("void", "onCreate", EnumSet.of(Modifier.PROTECTED), "Bundle",
                   "savedInstanceState");

    jw.emitStatement("super.onCreate(savedInstanceState)");
    jw.emitEmptyLine();

    jw.emitStatement("Loader loader = new DeepLinkLoader()");
    jw.emitStatement("DeepLinkRegistry registry = new DeepLinkRegistry(loader)");
    jw.emitStatement("Uri uri = getIntent().getData()");
    jw.emitStatement("String hostPath = uri.getHost() + uri.getPath()");
    jw.emitStatement("DeepLinkEntry entry = registry.parseUri(hostPath)");
    jw.emitEmptyLine();

    jw.beginControlFlow("if (entry != null)");
    jw.emitStatement("Map<String, String> parameterMap = entry.getParameters(hostPath)");
    jw.emitEmptyLine();

    jw.beginControlFlow("try");
    jw.emitStatement("Class<?> c = Class.forName(entry.getActivity())");
    jw.emitEmptyLine();

    jw.emitStatement("Intent intent");
    jw.beginControlFlow("if (entry.getType() == DeepLinkEntry.Type.CLASS)");
    jw.emitStatement("intent = new Intent(this, c)");
    jw.nextControlFlow("else");
    jw.emitStatement("Method method = c.getMethod(entry.getMethod(), Context.class)");
    jw.emitStatement("intent = (Intent) method.invoke(c, this)");
    jw.endControlFlow();
    jw.emitEmptyLine();

    jw.emitStatement("Bundle parameters = new Bundle()");
    jw.beginControlFlow("for (Map.Entry<String, String> parameterEntry : parameterMap.entrySet())");
    jw.emitStatement("parameters.putString(parameterEntry.getKey(), parameterEntry.getValue())");
    jw.endControlFlow();
    jw.emitStatement("intent.putExtras(parameters)");
    jw.emitEmptyLine();

    jw.emitStatement("startActivity(intent)");

    jw.nextControlFlow("catch (ClassNotFoundException exception)");
    jw.emitStatement("Log.e(TAG, \"Deep link to non-existent class: \" + entry.getActivity())");
    jw.nextControlFlow("catch (NoSuchMethodException exception)");
    jw.emitStatement("Log.e(TAG, \"Deep link to non-existent method: \" + entry.getMethod())");
    jw.nextControlFlow("catch (IllegalAccessException exception)");
    jw.emitStatement("Log.e(TAG, \"Could not deep link to method: \" + entry.getMethod())");
    jw.nextControlFlow("catch(InvocationTargetException  exception)");
    jw.emitStatement("Log.e(TAG, \"Could not deep link to method: \" + entry.getMethod())");
    jw.endControlFlow();

    jw.nextControlFlow("else");
    jw.emitStatement("Log.e(TAG, \"No registered entity to handle deep link: \" + uri.toString())");
    jw.endControlFlow();
    jw.emitEmptyLine();

    jw.emitStatement("finish()");

    jw.endMethod();

    jw.endType();

    jw.close();
  }
}
