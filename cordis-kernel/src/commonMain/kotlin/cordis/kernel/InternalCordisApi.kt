package cordis.kernel

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal to Cordis and bypasses the graph dispatcher; it may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class InternalCordisApi
