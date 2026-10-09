package io.github.ibcmanager.app;

import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.RuntimeConfigFactory;
import io.github.ibcmanager.diagnostics.DiagnosticBundleService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.ApplicationStartCoordinator;
import io.github.ibcmanager.runtime.DefaultProcessLauncher;
import io.github.ibcmanager.runtime.ListeningPortProbe;
import io.github.ibcmanager.runtime.IbcCommandClient;
import io.github.ibcmanager.runtime.LaunchScriptFactory;
import io.github.ibcmanager.runtime.ProcessIdentityStore;
import io.github.ibcmanager.runtime.ProcessTreeTerminator;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.runtime.ProfileRuntimeController;
import io.github.ibcmanager.runtime.RecoveryHistoryStore;
import io.github.ibcmanager.runtime.RecoverySleeper;
import io.github.ibcmanager.runtime.PortProbe;
import io.github.ibcmanager.runtime.RuntimeRegistry;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.DefaultCommandExecutor;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.UnavailableCredentialStore;
import io.github.ibcmanager.security.WindowsDpapiCredentialStore;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.task.TaskSchedulerService;
import io.github.ibcmanager.task.WindowsTaskSchedulerService;
import io.github.ibcmanager.validation.ProfileSetValidator;
import io.github.ibcmanager.validation.ProfileValidator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AppServices implements AutoCloseable {
    private final AppPaths paths;
    private final CredentialStore credentialStore;
    private final ProfileRepository profileRepository;
    private final ManagedConfigService managedConfigService;
    private final ProfileSaveService profileSaveService;
    private final ProfileDeletionService profileDeletionService;
    private final ProfileValidator profileValidator;
    private final ProfileSetValidator profileSetValidator;
    private final RuntimeRegistry runtimeRegistry;
    private final DiagnosticBundleService diagnosticBundleService;
    private final TaskSchedulerService taskSchedulerService;
    private final ExecutorService automaticRecoveryExecutor;

    private AppServices(AppPaths paths, CredentialStore credentialStore,
            ProfileRepository profileRepository, ManagedConfigService managedConfigService,
            ProfileSaveService profileSaveService, ProfileDeletionService profileDeletionService,
            ProfileValidator profileValidator, ProfileSetValidator profileSetValidator,
            RuntimeRegistry runtimeRegistry, DiagnosticBundleService diagnosticBundleService,
            TaskSchedulerService taskSchedulerService, ExecutorService automaticRecoveryExecutor) {
        this.paths = paths;
        this.credentialStore = credentialStore;
        this.profileRepository = profileRepository;
        this.managedConfigService = managedConfigService;
        this.profileSaveService = profileSaveService;
        this.profileDeletionService = profileDeletionService;
        this.profileValidator = profileValidator;
        this.profileSetValidator = profileSetValidator;
        this.runtimeRegistry = runtimeRegistry;
        this.diagnosticBundleService = diagnosticBundleService;
        this.taskSchedulerService = taskSchedulerService;
        this.automaticRecoveryExecutor = automaticRecoveryExecutor;
    }

    public static AppServices create(AppPaths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        FilePermissionHardener.hardenDirectory(paths.root());
        FilePermissionHardener.hardenDirectory(paths.profiles());
        FilePermissionHardener.hardenDirectory(paths.credentials());
        FilePermissionHardener.hardenDirectory(paths.runtime());
        FilePermissionHardener.hardenDirectory(paths.logs());
        FilePermissionHardener.hardenDirectory(paths.diagnostics());
        FilePermissionHardener.hardenDirectory(paths.deletions());

        OperatingSystem os = OperatingSystem.current();
        CredentialStore credentialStore = os == OperatingSystem.WINDOWS
                ? new WindowsDpapiCredentialStore(paths)
                : new UnavailableCredentialStore("Encrypted credential storage is available only on Windows");
        ProfileRepository repository = new ProfileRepository(paths);
        ManagedConfigService configService = new ManagedConfigService(paths);
        ProfileSaveService saveService = new ProfileSaveService(repository, credentialStore, configService);
        ProfileDeletionService deletionService = new ProfileDeletionService(paths, repository, credentialStore);
        deletionService.cleanupStaleTransactions();
        // Restore any older prepared deletion before converting the surviving profiles.
        new LegacyUpgradeService(paths).migrate();
        new ProfileConfigurationUpgradeService(paths, credentialStore).migrate();
        ProfileValidator validator = new ProfileValidator(credentialStore);
        ProfileSetValidator setValidator = new ProfileSetValidator();
        RuntimeConfigFactory runtimeConfigFactory = new RuntimeConfigFactory(paths, configService);
        LaunchScriptFactory launchFactory = new LaunchScriptFactory(paths, os);
        ProcessIdentityStore identityStore = new ProcessIdentityStore(paths);
        Clock clock = Clock.systemUTC();
        PortProbe portProbe = new ListeningPortProbe(os, new DefaultCommandExecutor());
        ApplicationStartCoordinator startCoordinator = new ApplicationStartCoordinator(
                Path.of(System.getProperty("java.io.tmpdir"), "IBCManager", "application-start-locks"),
                new OfflineApplicationLayoutResolver());
        DiagnosticBundleService diagnostics = new DiagnosticBundleService(paths, configService, validator, clock);
        RecoveryHistoryStore recoveryHistoryStore = new RecoveryHistoryStore(paths);
        ThreadFactory recoveryThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ibc-manager-auto-recovery");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService automaticRecoveryExecutor = Executors.newCachedThreadPool(recoveryThreadFactory);
        RuntimeRegistry registry = new RuntimeRegistry((Profile profile) -> new ProfileRuntimeController(
                profile,
                paths,
                validator,
                runtimeConfigFactory,
                credentialStore,
                launchFactory,
                new DefaultProcessLauncher(),
                portProbe,
                new IbcCommandClient(),
                identityStore,
                new ProcessTreeTerminator(),
                clock,
                startCoordinator,
                automaticRecoveryExecutor,
                recoveryHistoryStore,
                diagnostics::create,
                RecoverySleeper.system()));
        TaskSchedulerService scheduler = new WindowsTaskSchedulerService();
        return new AppServices(paths, credentialStore, repository, configService, saveService, deletionService, validator,
                setValidator, registry, diagnostics, scheduler, automaticRecoveryExecutor);
    }

    public AppPaths paths() { return paths; }
    public CredentialStore credentialStore() { return credentialStore; }
    public ProfileRepository profileRepository() { return profileRepository; }
    public ManagedConfigService managedConfigService() { return managedConfigService; }
    public ProfileSaveService profileSaveService() { return profileSaveService; }
    public ProfileDeletionService profileDeletionService() { return profileDeletionService; }
    public ProfileValidator profileValidator() { return profileValidator; }
    public ProfileSetValidator profileSetValidator() { return profileSetValidator; }
    public RuntimeRegistry runtimeRegistry() { return runtimeRegistry; }
    public DiagnosticBundleService diagnosticBundleService() { return diagnosticBundleService; }
    public TaskSchedulerService taskSchedulerService() { return taskSchedulerService; }

    @Override
    public void close() {
        runtimeRegistry.close();
        automaticRecoveryExecutor.shutdownNow();
        try {
            automaticRecoveryExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
