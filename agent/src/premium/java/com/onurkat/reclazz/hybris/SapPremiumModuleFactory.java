/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.PropertyFileSnapshots;
import com.onurkat.reclazz.platform.SapConfigFiles;
import com.onurkat.reclazz.hybris.interceptor.InterceptorReloader;
import com.onurkat.reclazz.hybris.impex.ImpexAutoImporter;
import com.onurkat.reclazz.hybris.codegen.CodegenReloader;
import com.onurkat.reclazz.hybris.backoffice.BackofficeConfigReloader;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.watcher.ChangeEvent;
import java.lang.instrument.Instrumentation;
import java.util.List;

/** Explicit optional module; all automation remains behind core admission. */
public final class SapPremiumModuleFactory implements PremiumModuleFactory {
    @Override public PremiumModule create(PremiumContext context) {
        return new Assembly(context).create();
    }

    private static final class Assembly {
        private final PremiumContext context;
        private final PlatformContext platformContext;
        private final PropertyFileSnapshots propertySnapshots;
        private final Instrumentation instrumentation;

        private Assembly(PremiumContext context) {
            this.context = context;
            platformContext = context.platform();
            propertySnapshots = context.propertySnapshots();
            instrumentation = context.instrumentation();
        }

        private PremiumModule create() {
            AgentConfig config = context.config();
            PlatformContext platform = platformContext;
            boolean sap = platform.getPlatformId() == PlatformContext.Platform.HYBRIS;
            InterceptorReloader interceptors = sap ? new InterceptorReloader() : null;
            ImpexAutoImporter importer = sap ? new ImpexAutoImporter(config.isImpexAllowRemove()) : null;
            CodegenReloader codegen;
            if (platform instanceof HybrisPlatformContext hybris) {
                java.util.function.Supplier<ClassLoader> loader = () -> {
                    Object app = platform.getApplicationContext();
                    return app != null ? app.getClass().getClassLoader() : platform.getClass().getClassLoader();
                };
                var updater = new com.onurkat.reclazz.hybris.HacRunningSystemUpdater(loader);
                codegen = new CodegenReloader(hybris.getHybrisContext(), config.isAutoUpdateRunningSystem(),
                        () -> context.requestRunningSystemUpdate() && updater.updateSchema());
            } else codegen = null;
            java.util.Map<Capability, PlatformChangeHandler> handlers = new java.util.EnumMap<>(Capability.class);
            // This dispatch also existed for non-SAP contexts; preserve it.
            handlers.put(Capability.BACKOFFICE_RELOAD, change -> {
                handleBackofficeConfigChange(changeEvent(change));
                return PlatformChangeHandler.Outcome.HANDLED;
            });
            java.util.Set<Capability> capabilities = java.util.EnumSet.of(Capability.BACKOFFICE_RELOAD);
            if (sap) {
                capabilities.addAll(java.util.EnumSet.of(Capability.INTERCEPTOR_REFRESH, Capability.IMPEX_IMPORT,
                        Capability.CONFIG_APPLY, Capability.LOCALIZATION_RELOAD));
                handlers.put(Capability.IMPEX_IMPORT, change -> {
                    handleImpexChange(changeEvent(change), importer);
                    return PlatformChangeHandler.Outcome.HANDLED;
                });
                handlers.put(Capability.CONFIG_APPLY, change -> {
                    handlePropertiesChange(changeEvent(change));
                    return PlatformChangeHandler.Outcome.HANDLED;
                });
                handlers.put(Capability.LOCALIZATION_RELOAD, change -> {
                    handleLocalizationChange(changeEvent(change));
                    return PlatformChangeHandler.Outcome.HANDLED;
                });
            }
            if (codegen != null) {
                capabilities.add(Capability.CODEGEN);
                capabilities.add(Capability.RUNNING_SYSTEM_UPDATE);
                handlers.put(Capability.CODEGEN, change -> {
                    handleCodegenXmlChange(changeEvent(change), codegen);
                    return PlatformChangeHandler.Outcome.HANDLED;
                });
            }
            return new PremiumModule() {
                @Override public java.util.Set<Capability> capabilities() { return capabilities; }
                @Override public java.util.Map<Capability, PlatformChangeHandler> changeHandlers() { return handlers; }
                @Override public PreparedRefresh prepareInterceptor(String name, List<Object> contexts) {
                    var refresh = interceptors == null ? null : interceptors.prepare(name, contexts);
                    return refresh == null ? null : refresh::complete;
                }
            };
        }

        private static ChangeEvent changeEvent(PlatformChangeHandler.Change change) {
            return new ChangeEvent(change.path(), ChangeEvent.Type.valueOf(change.eventType()),
                    change.moduleName(), change.sourceRoot());
        }

        private void handleBackofficeConfigChange(ChangeEvent event) {
            StatusReporter.info("Backoffice config changed: "
                    + event.getPath().getFileName() + " [" + event.getModuleName() + "]");
            int reset = BackofficeConfigReloader
                    .reload(event.getPath().getFileName().toString(),
                            platformContext.getAllApplicationContexts());
            if (reset == 0) {
                StatusReporter.info("No running backoffice holds this configuration "
                        + "(the backoffice web context is not up, or this is not a "
                        + "backoffice server). Nothing to reset.");
            }
        }

        private void handlePropertiesChange(ChangeEvent event) {
            String fileName = event.getPath().getFileName().toString();

            // A .properties file in an extension is far more often a message bundle
            // or a library's settings than the platform's configuration, and the
            // difference matters: applying one changes what the whole server reads,
            // and it takes no edit at all to arrive, a checked-out branch will do.
            if (platformContext instanceof HybrisPlatformContext
                    && !SapConfigFiles
                            .isPlatformConfiguration(event.getPath())) {
                StatusReporter.info(fileName + " is not platform configuration, so nothing was applied.");
                return;
            }

            StatusReporter.info("Config file changed: " + fileName);
            var candidate = fileName.endsWith(".properties") ? propertySnapshots.pending(event.getPath()) : null;
            if (candidate == null) return;
            if (candidate.changed().isEmpty()) {
                propertySnapshots.accept(candidate);
                StatusReporter.info("No property values changed.");
                return;
            }

            // On SAP Commerce the platform keeps its properties in memory and only
            // reads the files at startup, so an edit reaches nothing on its own.
            // The keys that actually differ are applied the same way the HAC
            // console applies them.
            if (platformContext instanceof HybrisPlatformContext) {
                // The platform's classloader, not the agent's: the agent is on the
                // system classpath and cannot see de.hybris.platform.util.Config
                // from there. The application context is loaded by the platform,
                // so its loader is the one that can.
                Object appContext = platformContext.getApplicationContext();
                ClassLoader platformLoader = appContext != null
                        ? appContext.getClass().getClassLoader()
                        : platformContext.getClass().getClassLoader();
                HybrisConfigReloader configReloader =
                        new HybrisConfigReloader(platformLoader, propertySnapshots);
                var propertyResult = configReloader.applyResult(candidate);
                java.util.List<String> applied = propertyResult.applied();
                context.applyLoggerLevels(candidate.content(), applied);

                if (applied.isEmpty() && propertyResult.reachable()) {
                    if (!propertyResult.pending().isEmpty()) return;
                    // The file was compared against the running configuration and
                    // held nothing new: a comment, a reformat, or a save with no
                    // edit. Warning about a restart here would be noise.
                    StatusReporter.info("No property values changed.");
                    return;
                }
                if (!applied.isEmpty()) {
                    StatusReporter.info("Applied "
                            + Plural.of(applied.size(), "property change") + ": "
                            + (applied.size() > 8 ? applied.subList(0, 8) + " …" : applied.toString()));
                    StatusReporter.info("Values read per request take effect now; "
                            + "anything consumed once at startup still needs a restart.");
                    return;
                }
            }

            java.util.Map<String, String> changed = candidate.changed();
            if (platformContext instanceof HybrisPlatformContext) {
                // Preserve the logger-only fallback when the platform Config API is absent.
                int levels = context.applyLoggerLevels(candidate.content(), changed.keySet());
                propertySnapshots.acceptKeys(candidate, changed.keySet().stream()
                        .filter(Assembly::isLoggingKey).toList());
                if (levels == 0 || !changed.keySet().stream().allMatch(Assembly::isLoggingKey))
                    reportUnconsumedProperties(changed);
                return;
            }
        }

        private static void reportUnconsumedProperties(java.util.Map<String, String> changed) {
            StatusReporter.warn("Values a bean read once at startup still need a restart; "
                    + "nothing here reads them again on its own.");
            RestartLedger.note(changed.keySet().toString(),
                    "changed, and a bean that read the value once at startup is still holding the old one");
        }

        private void handleLocalizationChange(ChangeEvent event) {
            String fileName = event.getPath().getFileName().toString();
            Object appContext = platformContext.getApplicationContext();
            ClassLoader platformLoader = appContext != null
                    ? appContext.getClass().getClassLoader()
                    : platformContext.getClass().getClassLoader();
            HybrisLocalizationReloader reloader =
                    new HybrisLocalizationReloader(
                            platformLoader, instrumentation);

            java.nio.file.Path parent = event.getPath().getParent();
            boolean backofficeLabels = parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().endsWith("-backoffice-labels");

            if (backofficeLabels) {
                if (reloader.reloadBackofficeLabels()) {
                    StatusReporter.success("Backoffice labels re-read. Reopen the view to see them.");
                } else {
                    // Backoffice loads ZK lazily; before anyone opens it there is
                    // no cache to clear and nothing to report.
                    StatusReporter.warn("Backoffice is not running here, so its labels were left alone.");
                }
                return;
            }

            if (reloader.reloadTypeLocalizations()) {
                StatusReporter.success("Type and enum names re-read from " + fileName + ".");
            } else {
                StatusReporter.warn("Could not reach the platform's localization cache; "
                        + fileName + " needs a restart to take effect.");
                RestartLedger.note(fileName, "localization text the platform's cache did not take");
            }
        }

        private void handleImpexChange(ChangeEvent event,
                                              ImpexAutoImporter impexImporter) {
            StatusReporter.info("ImpEx file changed: " + event.getPath().getFileName());
            impexImporter.importFile(event.getPath(),
                    ((HybrisPlatformContext) platformContext).getHybrisContext());
        }

        /**
         * Unified dispatch for both *-beans.xml and *-items.xml saves,
         * the {@link CodegenReloader}
         * picks the right kind from the file suffix and customises its
         * post-run reporting accordingly.
         */
        private void handleCodegenXmlChange(ChangeEvent event, CodegenReloader reloader) {
            if (reloader == null) {
                StatusReporter.info("Codegen XML changed: " + event.getPath().getFileName());
                StatusReporter.warn("Codegen reload requires a Hybris platform, not available here.");
                return;
            }
            reloader.handle(event);
        }
        private static boolean isLoggingKey(String key) {
            return key.startsWith("logging.level.") || key.startsWith("log4j2.logger.");
        }
    }
}
