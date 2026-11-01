package com.example.meetplatform.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** IANA region-based time-zone id such as {@code Asia/Kolkata}. Null is valid (use {@code @NotNull} to require). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTimeZone.Validator.class)
public @interface ValidTimeZone {

    String message() default "must be a valid IANA time zone";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTimeZone, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || TimeZones.isValid(value);
        }
    }
}
