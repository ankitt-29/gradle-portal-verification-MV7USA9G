package io.github.ankit.deploy

import org.gradle.api.Plugin
import org.gradle.api.Project

class LiferayOrderedDeployPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.extensions.create('orderedDeploy', LiferayOrderedDeployExtension)

        project.tasks.register('deployOrdered', DeployOrderedTask) {
            group       = 'Liferay Deploy'
            description = 'Deploys all Liferay OSGi modules in correct dependency order, verified via Gogo shell.'
        }

        // Wire jar task dependencies after all subprojects are evaluated,
        // so 'deployOrdered' automatically builds all JARs first.
        project.gradle.projectsEvaluated {
            project.tasks.named('deployOrdered').configure { deployTask ->
                project.subprojects.each { subProject ->
                    def jarTask = subProject.tasks.findByName('jar')
                    if (jarTask) deployTask.dependsOn(jarTask)
                }
            }
        }
    }
}
