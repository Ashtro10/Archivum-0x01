package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface ChecksSdkIntAtLeast { int api() default -1; String codename() default ""; int parameter() default -1; int lambda() default -1; }
