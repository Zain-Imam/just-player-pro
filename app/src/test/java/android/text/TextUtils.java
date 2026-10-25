package android.text;

// the stub android.jar leaves these unimplemented off a device
public class TextUtils {

    public static boolean isEmpty(final CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean equals(final CharSequence a, final CharSequence b) {
        return a == b || (a != null && b != null && a.toString().contentEquals(b));
    }
}
