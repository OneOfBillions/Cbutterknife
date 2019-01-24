package com.cttrip.bind;


import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;



@Target(METHOD)
@Retention(CLASS)
@ListenerClass(
    targetType = "android.view.View",
    setter = "setOnClickListener",
    type = "com.tts.trip.bind.CDebouncingOnClickListener",
    method = @ListenerMethod(
        name = "doClick",
        parameters = {"android.view.View","java.lang.String"}
    )
)
public @interface COnClick {
  /** View IDs to which the method will be bound. */
  String[] value() ;
}
