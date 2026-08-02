package io.github.ibcmanager.app;

import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.RuntimeConfigFactory;
import io.github.ibcmanager.diagnostics.DiagnosticBundleService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.DefaultProcessLauncher;
import io.github.ibcmanager.runtime.IbcCommandClient;
import io.github.ibcmanager.runtime.LaunchScriptFactory;
import io.github.ibcmanager.runtime.ProcessIdentityStore;
import io.github.ibcmanager.runtime.ProcessTreeTerminator;
import io.github.ibcmanager.runtime.ProfileRuntimeController;
import io.github.ibcmanager.runtime.RuntimeRegistry;
import io.github.ibcmanager.runtime.TcpPortProbe;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.UnavailableCredentialStore;
import io.github.ibcmanager.security.WindowsDpapiCredentialStore;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.task.TaskSchedulerService;
import io.github.ibcmanager.task.WindowsTaskSchedulerService;
import io.github.ibcmanager.validation.ProfileSetValidator;
import io.github.ibcmanager.validation.ProfileValidator;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

public final class AppServices implements AutoCloseable {
    private final AppPaths paths;
    private final CredentialStore credentialStore;
    private final ProfileRepository profileRepository;
    private final ManagedConfigService managedConfigService;
    private final ProfileSaveService profileSaveService;
    private final ProfileValidator profileValidator;
    private final ProfileSetValidator profileSetValidator;
    private final RuntimeRegistry runtimeRegistry;
    private final DiagnosticBundleService diagnosticBundleService;
    private final TaskSchedulerService taskSchedulerService;

    private AppServices(AppPaths paths, CredentialStore credentialStore,
            ProfileRepository profileRepository, ManagedConfigService managedConfigService,
            ProfileSaveService profileSaveService, ProfileValidator profileValidator, ProfileSetValidator profileSetValidator,
            RuntimeRegistry runtimeRegistry, DiagnosticBundleService diagnosticBundleService,
            TaskSchedulerService taskSchedulerService) {
        this.paths = paths;
        this.credentialStore = credentialStore;
        this.profileRepository = profileRepository;
        this.managedConfigService = managedConfigService;
        this.profileSaveService = profileSaveService;
        this.profileValidator = profileValidator;
        this.profileSetValidator = profileSetValidator;
        this.runtimeRegistry = runtimeRegistry;
        this.diagnosticBundleService = diagnosticBundleService;
        this.taskSchedulerService = taskSchedulerService;
    }

    public static AppServices create(AppPaths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        FilePermissionHardener.hardenDirectory(paths.root());
        FilePermissionHardener.hardenDirectory(paths.profiles());
        FilePermissionHardener.hardenDirectory(paths.credentials());
        FilePermissionHardener.hardenDirectory(paths.runtime());
        FilePermissionHardener.hardenDirectory(paths.logs());
        FilePermissionHardener.hardenDirectory(paths.diagnostics());

        OperatingSystem os = OperatingSystem.current();
        CredentialStore credentialStore = os == OperatingSystem.WINDOWS
                ? new WindowsDpapiCredentialStore(paths)
                : new UnavailableCredentialStore("Encrypted credential storage is available only on Windows");
        ProfileRepository repository = new ProfileRepository(paths);
        ManagedConfigService configService = new ManagedConfigService(paths);
        ProfileSaveService saveService = new ProfileSaveService(repository, credentialStore, configService);
        ProfileValidator validator = new ProfileValidator(credentialStore);
        ProfileSetValidator setValidator = new ProfileSetValidator();
        RuntimeConfigFactory runtimeConfigFactory = new RuntimeConfigFactory(paths, configService);
        LaunchScriptFactory launchFactory = new LaunchScriptFactory(paths, os);
        ProcessIdentityStore identityStore = new ProcessIdentityStore(paths);
        Clock clock = Clock.systemUTC();
        RuntimeRegistry registry = new RuntimeRegistry((Profile profile) -> new ProfileRuntimeController(
                profile,
                paths,
                validator,
                runtimeConfigFactory,
                credentialStore,
                launchFactory,
                new DefaultProcessLauncher(),
                new TcpPortProbe(),
                new IbcCommandClient(),
                identityStore,
                new ProcessTreeTerminator(),
                clock));
        DiagnosticBundleService diagnostics = new DiagnosticBundleService(paths, configService, validator, clock);
        TaskSchedulerService scheduler = new WindowsTaskSchedulerService();
        return new AppServices(paths, credentialStore, repository, configService, saveService, validator,
                setValidator, registry, diagnostics, scheduler);
    }

    public AppPaths paths() { return paths; }
    public CredentialStore credentialStore() { return credentialStore; }
    public ProfileRepository profileRepository() { return profileRepository; }
    public ManagedConfigService managedConfigService() { return managedConfigService; }
    public ProfileSaveService profileSaveService() { return profileSaveService; }
    public ProfileValidator profileValidator() { return profileValidator; }
    public ProfileSetValidator profileSetValidator() { return profileSetValidator; }
    public RuntimeRegistry runtimeRegistry() { return runtimeRegistry; }
    public DiagnosticBundleService diagnosticBundleService() { return diagnosticBundleService; }
    public TaskSchedulerService taskSchedulerService() { return taskSchedulerService; }

    @Override
    public void close() {
        runtimeRegistry.close();
    }
}
