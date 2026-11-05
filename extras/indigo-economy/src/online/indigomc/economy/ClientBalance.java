package online.indigomc.economy;

/** The last balance (minor units) the server sent this client. Neutral holder: no LC or client classes. */
public final class ClientBalance {
    public static volatile long minor = 0;

    private ClientBalance() {}
}
