package com.lightchat.util;

public final class Eco {
    public static final String MODE_ECO = "ECO";
    public static final String MODE_STANDARD = "STANDARD";

    private Eco() {}

    /** cores < 4 or RAM < 1 GB -> economy mode (2015 phone). */
    public static String mode(int cores, long ramMb) {
        return (cores < 4 || ramMb < 1024) ? MODE_ECO : MODE_STANDARD;
    }
}
