package io.github.ankit.deploy

/**
 * Configuration block for the Liferay Ordered Deploy plugin.
 *
 * Usage in build.gradle:
 *
 *   orderedDeploy {
 *       deployDir        = "/opt/liferay/deploy"  // optional, auto-detected from gradle.properties
 *       waitDelaySeconds = 10                     // seconds to wait before polling Gogo shell (default: 10)
 *       gogoShellPort    = 11311                  // Liferay Gogo shell port (default: 11311)
 *   }
 */
class LiferayOrderedDeployExtension {
    String deployDir        = null   // auto-detected from liferay.workspace.home.dir / liferay.home
    int    waitDelaySeconds = 10
    int    gogoShellPort    = 11311
}
