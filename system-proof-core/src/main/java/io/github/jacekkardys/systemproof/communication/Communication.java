package io.github.jacekkardys.systemproof.communication;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Interaction and protocol requirements declared directly or through a composed annotation. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
public @interface Communication {
    String interaction();

    String protocol();

    String scheme() default "";

    /** HTTP request-response invocation. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "invocation", protocol = "http")
    @interface Http {}

    /** PostgreSQL resource access through JDBC. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(
        interaction = "resource-access",
        protocol = "jdbc-postgresql",
        scheme = "jdbc:postgresql"
    )
    @interface JdbcPostgresql {}

    /** SMPP session communication. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "session", protocol = "smpp")
    @interface Smpp {}

    /** AMQP messaging communication. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "messaging", protocol = "amqp")
    @interface Amqp {}

    /** Redis resource access. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "resource-access", protocol = "redis")
    @interface Redis {}

    /** Generic TCP session communication. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "session", protocol = "tcp")
    @interface Tcp {}
}
