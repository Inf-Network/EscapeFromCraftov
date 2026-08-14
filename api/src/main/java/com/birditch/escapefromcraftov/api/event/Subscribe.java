package com.birditch.escapefromcraftov.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a public method as a domain event listener for [EventBus#register(Object)].
/// The method must declare exactly one parameter whose type is a [CraftovEvent] subtype.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
}
