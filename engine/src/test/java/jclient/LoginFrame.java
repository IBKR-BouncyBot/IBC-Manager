package jclient;

/** Test double of the public TWS entrypoint signature, never included in the engine JAR. */
public final class LoginFrame {
    public static String[] received;
    private LoginFrame() { }
    public static void main(String[] args) { received = args.clone(); }
}
