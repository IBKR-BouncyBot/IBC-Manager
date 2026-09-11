package io.github.ibcmanager.install;

import java.io.IOException;

@FunctionalInterface
interface IbcReleaseResolver {
    IbcReleaseInfo resolveLatest(InstallProgress progress) throws IOException, IbcInstallationException;
}
