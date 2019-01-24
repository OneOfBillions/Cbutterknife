package com.cttrip.bind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ViewBindings {
  private final String id;
  private final Set<FieldViewBinding> fieldBindings = new LinkedHashSet<FieldViewBinding>();
  private final LinkedHashMap<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>>
          methodBindings = new LinkedHashMap<ListenerClass,
          Map<ListenerMethod, Set<MethodViewBinding>>>();


  ViewBindings(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public Collection<FieldViewBinding> getFieldBindings() {
    return fieldBindings;
  }



  public void addFieldBinding(FieldViewBinding fieldBinding) {
    fieldBindings.add(fieldBinding);
  }

  public List<ViewBinding> getRequiredBindings() {
    List<ViewBinding> requiredViewBindings = new ArrayList<ViewBinding>();
    for (FieldViewBinding fieldBinding : fieldBindings) {
      if (fieldBinding.isRequired()) {
        requiredViewBindings.add(fieldBinding);
      }
    }
    return requiredViewBindings;
  }
  public Map<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> getMethodBindings() {
    return methodBindings;
  }



  public boolean hasMethodBinding(ListenerClass listener, ListenerMethod method) {
    Map<ListenerMethod, Set<MethodViewBinding>> methods = methodBindings.get(listener);
    return methods != null && methods.containsKey(method);
  }

  public void addMethodBinding(ListenerClass listener, ListenerMethod method,
                               MethodViewBinding binding) {
    Map<ListenerMethod, Set<MethodViewBinding>> methods = methodBindings.get(listener);
    Set<MethodViewBinding> set = null;
    if (methods == null) {
      methods = new LinkedHashMap<ListenerMethod, Set<MethodViewBinding>>();
      methodBindings.put(listener, methods);
    } else {
      set = methods.get(method);
    }
    if (set == null) {
      set = new LinkedHashSet<MethodViewBinding>();
      methods.put(method, set);
    }
    set.add(binding);
  }
}
