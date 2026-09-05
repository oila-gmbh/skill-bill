package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGeneratedStaging
import skillbill.infrastructure.fs.FileSystemScaffoldInstallLink
import skillbill.infrastructure.fs.FileSystemScaffoldManifestPersistence
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.install.ScaffoldInstallLinkPort
import skillbill.ports.scaffold.manifest.ScaffoldManifestPersistencePort
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.staging.ScaffoldGeneratedStagingPort
import skillbill.scaffold.adapters.FileSystemScaffoldSourceLoader

internal interface RuntimeScaffoldProvides {
  @Provides @JvmSynthetic
  fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  @Provides @JvmSynthetic
  fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway): UnsupportedScaffoldGateway = gateway

  @Provides @JvmSynthetic
  fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway): ScaffoldCatalogGateway = gateway

  @Provides @JvmSynthetic
  fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader): ScaffoldSourceLoaderPort = adapter

  @Provides @JvmSynthetic
  fun scaffoldManifestPersistencePort(adapter: FileSystemScaffoldManifestPersistence): ScaffoldManifestPersistencePort =
    adapter

  @Provides @JvmSynthetic
  fun scaffoldGeneratedStagingPort(adapter: FileSystemScaffoldGeneratedStaging): ScaffoldGeneratedStagingPort = adapter

  @Provides @JvmSynthetic
  fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink): ScaffoldInstallLinkPort = adapter
}
