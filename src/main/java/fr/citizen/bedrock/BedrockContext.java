package fr.citizen.bedrock;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Indique, pour le fil d'exécution courant, si l'on est en train de préparer des données
 * pour un joueur console. Les conversions (blocs, objets...) ne s'appliquent que dans ce cas.
 */
public final class BedrockContext {
    private static final AtomicInteger OPEN_SESSIONS = new AtomicInteger();
    /** Raccourci très rapide : aucun joueur console connecté = aucune conversion à vérifier. */
    public static volatile boolean ANY = false;
    private static final ThreadLocal<BedrockSession> CURRENT = new ThreadLocal<>();

    private BedrockContext() {
    }

    public static boolean isActive() {
        return ANY && CURRENT.get() != null;
    }

    @Nullable
    public static BedrockSession current() {
        return ANY ? CURRENT.get() : null;
    }

    public static void set(@Nullable BedrockSession session) {
        if (session != null || CURRENT.get() != null) {
            CURRENT.set(session);
        }
    }

    public static <T> T with(BedrockSession session, Supplier<T> action) {
        BedrockSession previous = CURRENT.get();
        CURRENT.set(session);
        try {
            return action.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    static void sessionOpened() {
        OPEN_SESSIONS.incrementAndGet();
        ANY = true;
    }

    static void sessionClosed() {
        if (OPEN_SESSIONS.decrementAndGet() <= 0) {
            OPEN_SESSIONS.set(0);
            ANY = false;
        }
    }

    /** Utilisé par l'autotest pour activer les conversions sans vraie connexion. */
    public static void forceAny(boolean value) {
        ANY = value;
    }
}
