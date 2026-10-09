package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.AppServices;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.runtime.IbcLogStateParser;
import io.github.ibcmanager.runtime.ListenerObservation;
import io.github.ibcmanager.runtime.ManagedProcess;
import io.github.ibcmanager.runtime.ProfileRuntimeController;
import io.github.ibcmanager.ui.MainFrame;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only, visibly labelled screenshot of the real GUI; no Gateway process is launched. */
final class GuiScreenshotFixture {
    private GuiScreenshotFixture() { }

    static void capture(MainFrame frame, AppServices services, Profile original, Path output) throws Exception {
        // Stop the real scheduler before supplying inert display snapshots. No runtime probe can
        // overwrite the fixture, and no display PID is ever looked up or terminated.
        services.runtimeRegistry().close();
        Profile demo = original.toBuilder().name("Demo Gateway - paper").twsMajorVersion("1050").build();
        Profile paused = TestSupport.validProfile(services.paths().root().resolve("screenshot-paused"))
                .toBuilder().name("Demo paused session").apiPort(4012).commandServerPort(7472).build();
        services.profileRepository().save(demo);
        services.profileRepository().save(paused);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ((Timer) field(frame, "uiTimer")).stop();
                    invoke(frame, "loadProfiles");
                    Method select = MainFrame.class.getDeclaredMethod("selectProfile", java.util.UUID.class);
                    select.setAccessible(true);
                    select.invoke(frame, demo.id());
                    ProfileRuntimeController live = services.runtimeRegistry().controller(demo.id()).orElseThrow();
                    ProfileRuntimeController idle = services.runtimeRegistry().controller(paused.id()).orElseThrow();
                    Instant at = Instant.parse("2026-10-09T08:15:00Z");
                    set(live, "process", new DisplayProcess(at));
                    IbcLogStateParser parser = (IbcLogStateParser) field(live, "stateParser");
                    parser.accept("Login has completed");
                    parser.markCommandServerOpen();
                    set(live, "apiListenerObservation", ListenerObservation.listening("127.0.0.1", 4002, 42024));
                    set(live, "status", new ProfileStatus(demo.id(), RuntimeState.API_LISTENER_DETECTED,
                            true, true, PortListenerState.LISTENING, 42024, at, null,
                            "Gateway's API listener is available on 127.0.0.1:4002 (PID 42024).", at));
                    set(idle, "status", new ProfileStatus(paused.id(), RuntimeState.PAUSED,
                            false, false, PortListenerState.NOT_LISTENING, -1, null, 0,
                            "Gateway is paused. Use Start to resume the session.", at));
                    invoke(frame, "refreshSelected");
                    ((JLabel) field(frame, "statusBar")).setText("IBC Manager " + Version.VERSION
                            + " GUI smoke test - simulated paper profiles; no Gateway or broker connection");
                    frame.setExtendedState(java.awt.Frame.NORMAL);
                    frame.setBounds(60, 60, 1280, 820);
                    frame.validate();
                    frame.toFront();
                    frame.repaint();
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            if (failure.get() != null) throw new AssertionError("Screenshot fixture setup failed", failure.get());
            Robot robot = new Robot();
            robot.waitForIdle();
            Thread.sleep(350);
            Toolkit.getDefaultToolkit().sync();
            AtomicReference<Rectangle> bounds = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> bounds.set(new Rectangle(frame.getLocationOnScreen(), frame.getSize())));
            BufferedImage image = robot.createScreenCapture(bounds.get());
            Path target = output.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            if (!ImageIO.write(image, "png", target.toFile())) throw new IllegalStateException("No PNG writer");
            BufferedImage verified = ImageIO.read(target.toFile());
            if (verified == null || verified.getWidth() != frame.getWidth()
                    || verified.getHeight() != frame.getHeight()) {
                throw new AssertionError("Screenshot did not round-trip at the real window dimensions");
            }
            System.out.println("GUI SCREENSHOT PASSED: " + target + " (" + image.getWidth() + "x"
                    + image.getHeight() + "; simulated paper profiles)");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (ProfileRuntimeController controller : services.runtimeRegistry().controllers()) {
                        set(controller, "process", null);
                        set(controller, "status", ProfileStatus.stopped(controller.profile().id()));
                        ((IbcLogStateParser) field(controller, "stateParser")).reset();
                    }
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                } catch (Exception ex) {
                    frame.dispose();
                    failure.set(ex);
                }
            });
            if (failure.get() != null) throw new AssertionError("Screenshot fixture cleanup failed", failure.get());
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void invoke(Object object, String name) throws ReflectiveOperationException {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }

    private record DisplayProcess(Instant started) implements ManagedProcess {
        @Override public long pid() { return 42024; }
        @Override public boolean isAlive() { return true; }
        @Override public Optional<Instant> startInstant() { return Optional.of(started); }
        @Override public List<ProcessHandle> descendants() { return List.of(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return new CompletableFuture<>(); }
        @Override public OptionalInt exitCode() { return OptionalInt.empty(); }
        @Override public boolean waitFor(Duration timeout) { throw new AssertionError("Display fixture is inert"); }
        @Override public void destroy() { throw new AssertionError("Display fixture cannot terminate a process"); }
        @Override public void destroyForcibly() { throw new AssertionError("Display fixture cannot terminate a process"); }
    }
}
