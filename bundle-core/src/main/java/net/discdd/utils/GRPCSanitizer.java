package net.discdd.utils;
import java.security.InvalidParameterException;
import java.util.regex.*;
public class GRPCSanitizer {
    private static final String stringToMatch = "^[a-zA-Z0-9-_=]+$";

    public static void checkIdClean(String s) {
        // [a-zA-Z0-9+-] matches alphanumeric characters or + or -
        Pattern p = Pattern.compile(stringToMatch);
        final Matcher m = p.matcher(s);
        if(!m.matches() || s.length() > 100){
            throw new InvalidParameterException("Not URL Encoded");
        }
    }
    public static void main(String args[]){
        checkIdClean("jw6rKsRqxJLQK6EFdKWJQrXDMAYeZ4eiao4IiWX_mpOp1zeN3Z9qyWVb7Wdu9Snk");
    }
}
