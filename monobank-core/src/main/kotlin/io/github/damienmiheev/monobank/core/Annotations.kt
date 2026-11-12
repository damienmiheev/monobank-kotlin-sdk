package io.github.damienmiheev.monobank.core

/**
 * Marks declarations that are public only so the SDK's own modules can share them.
 * They are not part of the stable API and can change in any release.
 */
@RequiresOptIn(
    message = "Internal monobank SDK API. It can change in any release without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalMonobankApi

/**
 * Marks APIs whose behaviour against the real monobank service has not been confirmed by a
 * live test yet (for example the corporate API's request signing). They work against the
 * documentation but may change in a minor release.
 *
 * Java callers are not forced to opt in; the KDoc of every such API repeats this warning.
 */
@RequiresOptIn(
    message =
        "Experimental monobank API: not yet confirmed against the live service. " +
            "It may change in a minor release.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class ExperimentalMonobankApi
