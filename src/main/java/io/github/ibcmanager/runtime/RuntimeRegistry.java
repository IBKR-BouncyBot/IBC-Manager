package io.github.ibcmanager.runtime;

import io.github.ibcmanager.logging.AppLog;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RuntimeRegistry implements AutoCloseable {
    private static final Logger LOG = AppLog.get(RuntimeRegistry.class);
    private final Map<UUID, ProfileRuntimeController> controllers = new ConcurrentHashMap<>();
    private final Function<Profile, ProfileRuntimeController> controllerFactory;
    private final ScheduledExecutorService scheduler;
    private final CopyOnWriteArrayList<Consumer<ProfileStatus>> listeners = new CopyOnWriteArrayList<>();

    public RuntimeRegistry(Function<Profile, ProfileRuntimeController> controllerFactory) {
        this.controllerFactory = Objects.requireNonNull(controllerFactory, "controllerFactory");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ibc-manager-monitor");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    LOG.log(Level.SEVERE, "Unhandled runtime monitor exception", error));
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleWithFixedDelay(this::refreshAllSafely, 0, 2, TimeUnit.SECONDS);
    }

    public void setProfiles(Collection<Profile> profiles) {
        for (Profile profile : profiles) upsert(profile);
        Set<UUID> retained = profiles.stream().map(Profile::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        controllers.entrySet().removeIf(entry -> !retained.contains(entry.getKey())
                && !entry.getValue().status().processAlive());
    }

    public ProfileRuntimeController upsert(Profile profile) {
        return controllers.compute(profile.id(), (id, existing) -> {
            if (existing == null) {
                ProfileRuntimeController created = controllerFactory.apply(profile);
                created.addStatusListener(this::publish);
                return created;
            }
            if (!existing.profile().equals(profile) && !existing.status().processAlive()) existing.updateProfile(profile);
            return existing;
        });
    }

    public Optional<ProfileRuntimeController> controller(UUID profileId) {
        return Optional.ofNullable(controllers.get(profileId));
    }

    public List<ProfileRuntimeController> controllers() {
        List<ProfileRuntimeController> result = new ArrayList<>(controllers.values());
        result.sort(Comparator.comparing(controller -> controller.profile().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public void addStatusListener(Consumer<ProfileStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStatusListener(Consumer<ProfileStatus> listener) {
        listeners.remove(listener);
    }

    public void startAutoStartProfiles() {
        for (ProfileRuntimeController controller : controllers()) {
            if (controller.profile().enabled() && controller.profile().autoStart() && !controller.status().processAlive()) {
                scheduler.execute(() -> {
                    try { controller.start(); }
                    catch (RuntimeControllerException ex) { controller.logs().append("IBC Manager auto-start failed: " + ex.getMessage()); }
                });
            }
        }
    }

    public List<ProfileRuntimeController> controllersBlockingManagerExit() {
        return controllers().stream().filter(controller -> !controller.prepareForManagerExit()).toList();
    }

    private void refreshAllSafely() {
        for (ProfileRuntimeController controller : controllers.values()) {
            try { controller.refresh(); }
            catch (RuntimeException ex) { controller.logs().append("IBC Manager monitor error: " + ex.getMessage()); }
        }
    }

    private void publish(ProfileStatus status) {
        for (Consumer<ProfileStatus> listener : listeners) {
            try {
                listener.accept(status);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Runtime status listener failed", ex);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
