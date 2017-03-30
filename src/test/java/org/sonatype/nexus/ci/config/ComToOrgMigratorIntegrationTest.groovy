/*
 * Copyright (c) 2016-present Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.sonatype.nexus.ci.config

import com.sonatype.insight.scan.model.Scan
import com.sonatype.nexus.api.iq.ApplicationPolicyEvaluation
import com.sonatype.nexus.api.iq.internal.InternalIqClient
import com.sonatype.nexus.api.iq.internal.InternalIqClientBuilder
import com.sonatype.nexus.api.iq.scan.ScanResult

import org.sonatype.nexus.ci.iq.IqPolicyEvaluatorBuildStep
import org.sonatype.nexus.ci.nxrm.MavenPackage
import org.sonatype.nexus.ci.nxrm.NexusPublisher
import org.sonatype.nexus.ci.nxrm.NexusPublisherBuildStep
import org.sonatype.nexus.ci.nxrm.PackageUploaderUtil

import hudson.model.FreeStyleProject
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.Rule
import org.jvnet.hudson.test.JenkinsRule
import spock.lang.Specification

class ComToOrgMigratorIntegrationTest
    extends Specification
{
  @Rule
  public JenkinsRule jenkins

  private InternalIqClient iqClient

  def setup() {
    def classLoader = getClass().getClassLoader();
    def file = new File(classLoader.getResource('org/sonatype/nexus/ci/config/ComToOrgMigratorIntegrationTest').getFile());
    jenkins = new JenkinsRule().withExistingHome(file)

    GroovyMock(InternalIqClientBuilder, global: true)
    def iqClientBuilder = Mock(InternalIqClientBuilder)
    InternalIqClientBuilder.create() >> iqClientBuilder

    iqClientBuilder.withProxyConfig(_) >> iqClientBuilder
    iqClientBuilder.withServerConfig(_) >> iqClientBuilder
    iqClientBuilder.withLogger(_) >> iqClientBuilder
    iqClientBuilder.withInstanceId(_) >> iqClientBuilder

    iqClient = Mock(InternalIqClient)
    iqClientBuilder.build() >> iqClient

    GroovyMock(PackageUploaderUtil.class, global: true)
  }

  def 'it migrates the global IQ configuration'() {
    when:
      def globalNexusConfiguration = GlobalNexusConfiguration.globalNexusConfiguration

    then:
      globalNexusConfiguration.iqConfigs.size() == 1

      def nxiqConfiguration = globalNexusConfiguration.iqConfigs[0]
      nxiqConfiguration.serverUrl.toString() == 'http://localhost:8080'
      !nxiqConfiguration.isPkiAuthentication
      nxiqConfiguration.credentialsId == 'user'
  }

  def 'it migrates the global RM configuration'() {
    when:
      def globalNexusConfiguration = GlobalNexusConfiguration.globalNexusConfiguration

    then:
      globalNexusConfiguration.nxrmConfigs.size() == 1

      def nxrmConfiguration = globalNexusConfiguration.nxrmConfigs[0]
      nxrmConfiguration.id == 'nexus-rm'
      nxrmConfiguration.internalId == '605a8c1e-5152-4dbd-b41e-0d1b26a66317'
      nxrmConfiguration.displayName == 'Nexus Repository Manager'
      nxrmConfiguration.serverUrl == 'http://localhost:8070'
      nxrmConfiguration.credentialsId == 'user'
  }

  def 'it migrates a Freestyle IQ job'() {
    when:
      def project = (FreeStyleProject)jenkins.jenkins.getItem('Freestyle-IQ')
      def buildStep = (IqPolicyEvaluatorBuildStep)project.builders[0]

    then: 'the fields are properly migrated'
      buildStep.iqStage == 'build'
      buildStep.iqApplication == 'sample-app'
      buildStep.failBuildOnNetworkError
      buildStep.jobCredentialsId == 'user2'
      buildStep.iqScanPatterns.size() == 1
      buildStep.iqScanPatterns[0].scanPattern == 'target/*.jar'

    and: 'a build is run'
      def build = project.scheduleBuild2(0).get()

    then: 'the application is scanned and evaluated'
      1 * iqClient.scan(*_) >> new ScanResult(new Scan(), File.createTempFile('dummy-scan', '.xml.gz'))
      1 * iqClient.evaluateApplication('sample-app', 'build', _) >> new ApplicationPolicyEvaluation(0, 1, 2, 3, [], false,
          'http://server/link/to/report')

    then: 'the return code is successful'
      jenkins.assertBuildStatusSuccess(build)
  }

  def 'it migrates a Pipeline IQ job'() {
    when: 'a build is run'
      def project = (WorkflowJob)jenkins.jenkins.getItem('Pipeline-IQ')
      def build = project.scheduleBuild2(0).get()

    then: 'the application is scanned and evaluated'
      1 * iqClient.scan(*_) >> new ScanResult(new Scan(), File.createTempFile('dummy-scan', '.xml.gz'))
      1 * iqClient.evaluateApplication('sample-app', 'build', _) >> new ApplicationPolicyEvaluation(0, 1, 2, 3, [], false,
          'http://server/link/to/report')

    then: 'the expected result is returned'
      jenkins.assertBuildStatusSuccess(build)
  }

  def 'it migrates a Freestyle RM job'() {
    when:
      def project = (FreeStyleProject)jenkins.jenkins.getItem('Freestyle-RM')
      def buildStep = (NexusPublisherBuildStep)project.builders[0]

    then: 'the fields are properly migrated'
      buildStep.nexusInstanceId == 'nexus-rm'
      buildStep.nexusRepositoryId == 'repo'
      buildStep.packages.size() == 1
      def stepMavenPackage = (MavenPackage)buildStep.packages[0]
      stepMavenPackage.coordinate.groupId == 'g'
      stepMavenPackage.coordinate.artifactId == 'a'
      stepMavenPackage.coordinate.version == 'v'
      stepMavenPackage.coordinate.packaging == 'txt'
      stepMavenPackage.assets.size() == 1
      def stepMavenAsset = stepMavenPackage.assets[0]
      stepMavenAsset.filePath == 'foo.txt'
      stepMavenAsset.classifier == 'c'
      stepMavenAsset.extension == 'e'

    and: 'a build is run'
      def build = project.scheduleBuild2(0).get()

    then: 'the package is uploaded'
      1 * PackageUploaderUtil.uploadPackage(*_) >> { arguments ->
        def nxrmPublisher = (NexusPublisher)arguments[0]
        assert nxrmPublisher.nexusInstanceId == 'nexus-rm'
        assert nxrmPublisher.nexusRepositoryId == 'repo'
        assert nxrmPublisher.packages.size() == 1
        def mavenPackage = (MavenPackage)nxrmPublisher.packages[0]
        assert mavenPackage.coordinate.groupId == 'g'
        assert mavenPackage.coordinate.artifactId == 'a'
        assert mavenPackage.coordinate.version == 'v'
        assert mavenPackage.coordinate.packaging == 'txt'
        assert mavenPackage.assets.size() == 1
        def mavenAsset = mavenPackage.assets[0]
        assert mavenAsset.filePath == 'foo.txt'
        assert mavenAsset.classifier == 'c'
        assert mavenAsset.extension == 'e'
      }

    then: 'the expected result is returned'
      jenkins.assertBuildStatusSuccess(build)
  }

  def 'it migrates a Pipeline RM job'() {
    when: 'a build is run'
      def project = (WorkflowJob)jenkins.jenkins.getItem('Pipeline-RM')
      def build = project.scheduleBuild2(0).get()

    then: 'the package is uploaded'
      1 * PackageUploaderUtil.uploadPackage(*_) >> { arguments ->
        def nxrmPublisher = (NexusPublisher)arguments[0]
        assert nxrmPublisher.nexusInstanceId == 'nexus-rm'
        assert nxrmPublisher.nexusRepositoryId == 'repo'
        assert nxrmPublisher.packages.size() == 1
        def mavenPackage = (MavenPackage)nxrmPublisher.packages[0]
        assert mavenPackage.coordinate.groupId == 'g'
        assert mavenPackage.coordinate.artifactId == 'a'
        assert mavenPackage.coordinate.version == 'v'
        assert mavenPackage.coordinate.packaging == 'txt'
        assert mavenPackage.assets.size() == 1
        def mavenAsset = mavenPackage.assets[0]
        assert mavenAsset.filePath == 'foo.txt'
        assert mavenAsset.classifier == 'c'
        assert mavenAsset.extension == 'e'
      }

    then: 'the expected result is returned'
      jenkins.assertBuildStatusSuccess(build)
  }
}