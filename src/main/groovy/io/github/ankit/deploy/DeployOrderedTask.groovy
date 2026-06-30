package io.github.ankit.deploy

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.jar.JarFile
import org.apache.commons.net.telnet.TelnetClient

abstract class DeployOrderedTask extends DefaultTask {

    @Inject
    abstract FileSystemOperations getFileSystemOperations()

    @TaskAction
    void deployOrdered() {
        def extension = project.extensions.findByType(LiferayOrderedDeployExtension)

        project.logger.lifecycle("")
        project.logger.lifecycle("╔══════════════════════════════════════════════════════╗")
        project.logger.lifecycle("║     Liferay Ordered Deploy Plugin                    ║")
        project.logger.lifecycle("║     by io.github.ankit.deploy                        ║")
        project.logger.lifecycle("╚══════════════════════════════════════════════════════╝")
        project.logger.lifecycle("")

        String deployDir = resolveDeployDir(extension)
        project.logger.lifecycle("→ Deploy folder : ${deployDir}")

        def deployDirFile = new File(deployDir)
        if (!deployDirFile.exists()) {
            throw new org.gradle.api.GradleException(
                "Deploy folder not found at '${deployDir}'. " +
                "Set liferay.workspace.home.dir or liferay.home in gradle.properties / gradle-local.properties."
            )
        }

        int delaySeconds = extension?.waitDelaySeconds ?: 10
        int gogoPort     = extension?.gogoShellPort    ?: 11311

        project.logger.lifecycle("→ Gogo shell    : localhost:${gogoPort}")
        project.logger.lifecycle("→ Wave delay    : ${delaySeconds}s")
        project.logger.lifecycle("")

        List<Project> allModules = collectModuleProjects(project)
        project.logger.lifecycle("→ Found ${allModules.size()} OSGi module projects")
        project.logger.lifecycle("")

        Map<Project, Set<Project>> graph = buildDependencyGraph(allModules)
        List<List<Project>> waves = topoSortIntoWaves(graph)

        printWaves(waves)

        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        project.logger.lifecycle("  DEPLOYING")
        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        int totalDeployed = 0
        int totalSkipped  = 0
        def self = this

        waves.eachWithIndex { wave, waveIndex ->
            project.logger.lifecycle("")
            project.logger.lifecycle("  ┌─ WAVE ${waveIndex + 1} of ${waves.size()} (${wave.size()} module(s))")

            List<File> deployedJars = []
            wave.each { subProject ->
                File jar = self.deployProject(subProject, deployDir)
                if (jar != null) { deployedJars << jar; totalDeployed++ }
                else              { totalSkipped++ }
            }

            project.logger.lifecycle("  │")
            project.logger.lifecycle("  │   Dropped ${deployedJars.size()} JAR(s) into deploy folder.")

            if (!deployedJars.isEmpty()) {
                if (delaySeconds > 0) {
                    project.logger.lifecycle("  │   ⏳ Waiting ${delaySeconds}s for Liferay to pick up this wave...")
                    Thread.sleep(delaySeconds * 1000L)
                }

                project.logger.lifecycle("  │   🔍 Verifying bundle states via Gogo shell...")

                Map<String, Boolean> bundleMap = [:]
                deployedJars.each { jarFile ->
                    String bsn = self.getBundleSymbolicNameFromJar(jarFile)
                    if (bsn != null) bundleMap[bsn] = self.isFragmentBundle(jarFile)
                }

                if (!bundleMap.isEmpty()) {
                    boolean allReady = false
                    int attempts = 30
                    for (int i = 0; i < attempts; i++) {
                        if (self.checkAllBundlesActive(bundleMap, gogoPort)) {
                            allReady = true
                            break
                        }
                        if (i % 5 == 0) {
                            project.logger.lifecycle("  │   ⏳ Waiting... (attempt ${i + 1}/${attempts})")
                        }
                        Thread.sleep(3000)
                    }

                    if (allReady) {
                        project.logger.lifecycle("  │   ✅ All ${bundleMap.size()} bundle(s) reached expected state.")
                    } else {
                        project.logger.warn("  │   ⚠️  Some bundles did not reach expected state in time.")
                    }
                } else {
                    project.logger.warn("  │   ⚠️  Could not read Bundle-SymbolicName for any JAR in this wave.")
                }

                if (waveIndex < waves.size() - 1) {
                    project.logger.lifecycle("  │   → Proceeding to next wave.")
                }
            }

            project.logger.lifecycle("  └─────────────────────────────────────────")
        }

        project.logger.lifecycle("")
        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        project.logger.lifecycle("  DEPLOYMENT COMPLETE")
        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        project.logger.lifecycle("")
        project.logger.lifecycle("  ✅ Deployed : ${totalDeployed} modules")
        project.logger.lifecycle("  ⤷ Skipped  : ${totalSkipped} modules (no JAR output)")
        project.logger.lifecycle("  📁 Location : ${deployDir}")
        project.logger.lifecycle("")
    }

    protected void printWaves(List<List<Project>> waves) {
        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        project.logger.lifecycle("  DEPLOYMENT PLAN (${waves.size()} waves, ${waves.sum { it.size() }} modules)")
        project.logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        waves.eachWithIndex { wave, idx ->
            project.logger.lifecycle("")
            project.logger.lifecycle("  ┌─ WAVE ${idx + 1} of ${waves.size()} (${wave.size()} module(s))")
            wave.each { proj -> project.logger.lifecycle("  │   ✦ ${proj.path}") }
            project.logger.lifecycle("  └─────────────────────────────────────────")
        }
        project.logger.lifecycle("")
    }

    protected File deployProject(Project subProject, String deployDir) {
        def jarTask = subProject.tasks.findByName('jar')
        if (!jarTask) {
            project.logger.lifecycle("  │   ⤷ SKIP  ${subProject.path} (no jar task)")
            return null
        }

        def jarFile = jarTask.outputs.files.files.find { file ->
            file.name.endsWith('.jar') &&
            !file.name.endsWith('-sources.jar') &&
            !file.name.endsWith('-tests.jar') &&
            !file.name.endsWith('-javadoc.jar') &&
            !file.name.endsWith('-test.jar')
        }

        if (!jarFile || !jarFile.exists()) {
            project.logger.lifecycle("  │   ⤷ SKIP  ${subProject.path} (JAR not found)")
            return null
        }

        fileSystemOperations.copy { from jarFile; into deployDir }
        project.logger.lifecycle("  │   ✅ ${jarFile.name}")
        return jarFile
    }

    protected String resolveDeployDir(LiferayOrderedDeployExtension extension) {
        if (extension?.deployDir) return extension.deployDir

        def props = new Properties()

        def gradleProps = new File(project.rootDir, "gradle.properties")
        if (gradleProps.exists()) gradleProps.withInputStream { props.load(it) }

        def gradleLocalProps = new File(project.rootDir, "gradle-local.properties")
        if (gradleLocalProps.exists()) gradleLocalProps.withInputStream { props.load(it) }

        def liferayHome = props.getProperty("liferay.workspace.home.dir") ?: props.getProperty("liferay.home")

        if (liferayHome) {
            def home = new File(liferayHome)
            if (!home.isAbsolute()) home = new File(project.rootDir, liferayHome)
            return new File(home, "deploy").absolutePath
        }

        return new File(project.rootDir, "bundles/deploy").absolutePath
    }

    protected List<Project> collectModuleProjects(Project rootProject) {
        return rootProject.subprojects.findAll { subProject ->
            boolean underModules  = subProject.projectDir.absolutePath.replace('\\', '/').contains('/modules/')
            boolean hasJavaPlugin = subProject.plugins.hasPlugin('java') || subProject.plugins.hasPlugin('groovy')
            boolean hasBndFile    = new File(subProject.projectDir, 'bnd.bnd').exists()
            return underModules && (hasJavaPlugin || hasBndFile)
        }.toList()
    }

    protected Map<Project, Set<Project>> buildDependencyGraph(List<Project> allModules) {
        Map<Project, Set<Project>> graph = [:]
        allModules.each { graph[it] = [] as Set<Project> }

        def configs = ['implementation', 'api', 'compileOnly', 'runtimeOnly', 'compileInclude', 'provided']

        allModules.each { subProject ->
            configs.each { configName ->
                def config = subProject.configurations.findByName(configName)
                if (config == null) return
                try {
                    config.dependencies.each { dep ->
                        if (dep instanceof org.gradle.api.artifacts.ProjectDependency) {
                            def dep_ = dep.dependencyProject
                            if (graph.containsKey(dep_)) graph[subProject] << dep_
                        }
                    }
                } catch (Exception e) {
                    project.logger.debug("[skip] Config '${configName}' on ${subProject.path}: ${e.message}")
                }
            }
        }
        return graph
    }

    protected List<List<Project>> topoSortIntoWaves(Map<Project, Set<Project>> graph) {
        List<List<Project>> waves = []
        Set<Project> deployed  = [] as Set<Project>
        Set<Project> remaining = new HashSet<>(graph.keySet())

        while (!remaining.isEmpty()) {
            List<Project> wave = remaining.findAll { proj ->
                (graph[proj] ?: ([] as Set)).every { dep -> deployed.contains(dep) }
            }.toList()

            if (wave.isEmpty()) {
                project.logger.warn("⚠️  Circular dependency detected — placing ${remaining.size()} remaining module(s) in final wave.")
                wave = new ArrayList<>(remaining)
            }

            waves << wave.sort { it.path }
            deployed.addAll(wave)
            remaining.removeAll(wave)
        }
        return waves
    }

    protected String getBundleSymbolicNameFromJar(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile)
            def bsn = jar.getManifest().getMainAttributes().getValue("Bundle-SymbolicName")
            jar.close()
            return (bsn?.contains(";")) ? bsn.substring(0, bsn.indexOf(";")) : bsn
        } catch (Exception e) {
            project.logger.debug("Could not read Bundle-SymbolicName from ${jarFile.name}: ${e.message}")
            return null
        }
    }

    protected boolean isFragmentBundle(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile)
            def fragmentHost = jar.getManifest().getMainAttributes().getValue("Fragment-Host")
            jar.close()
            return fragmentHost != null
        } catch (Exception ignored) {
            return false
        }
    }

    /**
     * Connects to the Liferay Gogo shell and verifies each bundle has reached its expected state.
     * Standard bundles must be Active; OSGi fragment bundles are accepted in Resolved state.
     */
    protected boolean checkAllBundlesActive(Map<String, Boolean> bundleMap, int port) {
        try {
            TelnetClient telnet = new TelnetClient()
            telnet.setConnectTimeout(3000)
            telnet.connect("localhost", port)
            def out      = telnet.getOutputStream()
            def inStream = telnet.getInputStream()

            // Drain the initial welcome banner and wait for the 'g!' prompt
            long start = System.currentTimeMillis()
            StringBuilder welcome = new StringBuilder()
            byte[] buf = new byte[1024]
            while (System.currentTimeMillis() - start < 2000) {
                if (inStream.available() > 0) {
                    int r = inStream.read(buf)
                    if (r > 0) {
                        welcome.append(new String(buf, 0, r, "UTF-8"))
                        if (welcome.toString().trim().endsWith("g!")) break
                    }
                } else {
                    Thread.sleep(50)
                }
            }

            for (Map.Entry<String, Boolean> entry : bundleMap.entrySet()) {
                String  bsn        = entry.getKey()
                boolean isFragment = entry.getValue()

                out.write(("lb -s " + bsn + "\n").getBytes("UTF-8"))
                out.flush()

                long cmdStart = System.currentTimeMillis()
                StringBuilder response = new StringBuilder()
                boolean found = false

                while (System.currentTimeMillis() - cmdStart < 3000) {
                    if (inStream.available() > 0) {
                        int read = inStream.read(buf)
                        if (read > 0) {
                            response.append(new String(buf, 0, read, "UTF-8"))
                            String out_ = response.toString()

                            boolean stateMatched = isFragment
                                ? (out_.contains("Resolved") || out_.contains("RESOLVED") || out_.contains("Active") || out_.contains("ACTIVE"))
                                : (out_.contains("Active")   || out_.contains("ACTIVE"))

                            if (out_.contains(bsn) && stateMatched) found = true
                            if (out_.trim().endsWith("g!")) break
                        }
                    } else {
                        Thread.sleep(20)
                    }
                }

                if (!found) {
                    telnet.disconnect()
                    return false
                }
            }

            out.write(("disconnect\n").getBytes("UTF-8"))
            out.flush()
            telnet.disconnect()
            return true

        } catch (Exception e) {
            project.logger.warn("  │   ⚠️  Gogo shell unavailable on port ${port}: ${e.message}")
            project.logger.debug("Gogo shell error: ${e.message}", e)
            return false
        }
    }
}
