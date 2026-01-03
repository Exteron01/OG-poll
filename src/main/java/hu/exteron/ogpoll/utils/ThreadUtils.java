package hu.exteron.ogpoll.utils;

import com.artillexstudios.axapi.scheduler.Scheduler;
import org.bukkit.Bukkit;

public final class ThreadUtils {
    private ThreadUtils() {
    }

    public static void checkMain(String message) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Main thread check failed: " + message);
        }
    }

    public static void checkNotMain(String message) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Async thread check failed: " + message);
        }
    }

    public static void runAsync(Runnable runnable) {
        if (!Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Scheduler.get().runAsync(runnable);
        }
    }

    public static void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Scheduler.get().run(runnable);
        }
    }
}
