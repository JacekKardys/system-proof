package io.github.jacekkardys.systemproof.examples.sms.environment;

/** Stable contract identifiers shared by the SMS ingestion example topology. */
public final class SmsContractIds {
    public static final String SMSC_SMPP = "smpp";
    public static final String SMSC_CONTROL = "control";
    public static final String SMS_INGESTION = "sms";
    public static final String JASMIN_AMQP = "amqp";
    public static final String JASMIN_REDIS = "redis";
    public static final String JASMIN_ADMINISTRATION = "administration";
    public static final String SMS_DATABASE = "jdbc";

    private SmsContractIds() {}
}
