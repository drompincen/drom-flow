package com.acme.util;

public class Helper {
    public static final String VERSION = "1.0";

    private Helper() {
    }

    public static String help() {
        return "util-help";
    }

    public static String format(String value) {
        return VERSION + ":" + value;
    }
}
