/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package features.services.datastore

import framework.*
import framework.category.Slow
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.junit.experimental.categories.Category
import org.linkedin.util.clock.SystemClock
import org.rackspace.deproxy.Deproxy
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static javax.servlet.http.HttpServletResponse.SC_OK
import static javax.servlet.http.HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
import static org.linkedin.groovy.util.concurrent.GroovyConcurrentUtils.waitForCondition

@Category(Slow.class)
class RemoteDatastoreServiceTest extends Specification {
    @Shared
    def repose1

    @Shared
    def repose2

    @Shared
    def remote

    @Shared
    def deproxy

    @Shared
    def repose1Port

    @Shared
    def repose2Port

    @Shared
    def reposeOneEndpoint

    @Shared
    def reposeTwoEndpoint

    @Shared
    def remoteDatastoreEndpoint

    def setupSpec() {
        repose1Port = PortFinder.instance.getNextOpenPort()
        repose2Port = PortFinder.instance.getNextOpenPort()
        def remotePort = PortFinder.instance.getNextOpenPort()
        def datastorePort = PortFinder.instance.getNextOpenPort()
        def targetPort = PortFinder.instance.getNextOpenPort()

        reposeOneEndpoint = "http://localhost:$repose1Port"
        reposeTwoEndpoint = "http://localhost:$repose2Port"
        remoteDatastoreEndpoint = "http://localhost:$datastorePort"

        deproxy = new Deproxy()
        deproxy.addEndpoint(targetPort)

        def launcherAndLog1 = startRepose('repose1', repose1Port, targetPort, datastorePort, true)
        repose1 = launcherAndLog1.ReposeValveLauncher

        def launcherAndLog2 = startRepose('repose2', repose2Port, targetPort, datastorePort, true)
        repose2 = launcherAndLog2.ReposeValveLauncher

        def launcherAndLogR = startRepose('remote', remotePort, targetPort, datastorePort, false)
        remote = launcherAndLogR.ReposeValveLauncher

        waitUntilReadyToServiceRequests(launcherAndLog1.ReposeLogSearch)
        waitUntilReadyToServiceRequests(launcherAndLog2.ReposeLogSearch)
        waitUntilReadyToServiceRequests(launcherAndLogR.ReposeLogSearch)
    }

    def cleanupSpec() {
        deproxy?.shutdown()
        repose1?.stop()
        repose2?.stop()
        remote?.stop()
    }

    static def startRepose(String subName, int reposePort, int targetPort, int datastorePort, boolean client) {
        def testProperties = new TestProperties(RemoteDatastoreServiceTest.canonicalName.replace('.', '/') + "/$subName")
        testProperties.setReposePort(reposePort)
        testProperties.setTargetPort(targetPort)
        def reposeConfigProvider = new ReposeConfigurationProvider(testProperties)
        def reposeValveLauncher = new ReposeValveLauncher(reposeConfigProvider, testProperties)
        reposeValveLauncher.enableDebug()
        def reposeLogSearch = new ReposeLogSearch(testProperties.getLogFile())
        reposeLogSearch.cleanLog()
        def params = testProperties.getDefaultTemplateParams()
        params.put('datastorePort', datastorePort)
        reposeValveLauncher.configurationProvider.cleanConfigDirectory()
        reposeValveLauncher.configurationProvider.applyConfigs("common", params)
        reposeValveLauncher.configurationProvider.applyConfigs("features/services/datastore/remote", params)
        def type = client ? "client" : "datastore"
        reposeValveLauncher.configurationProvider.applyConfigs("features/services/datastore/remote/$type", params)
        reposeValveLauncher.start(false, false, "repose", type)
        return ['ReposeValveLauncher': reposeValveLauncher, 'ReposeLogSearch': reposeLogSearch]
    }

    static def waitUntilReadyToServiceRequests(ReposeLogSearch reposeLogSearch) {
        def clock = new SystemClock()
        try {
            waitForCondition(clock, '35s', '1s') {
                return (reposeLogSearch.awaitByString("Repose ready", 1, 35, TimeUnit.SECONDS).size() > 0)
            }
        } catch (TimeoutException ignored) {
            return false
        }
    }

    def "When a limit has not been reached, request should pass"() {
        given: "the rate-limit has not been reached"
        def headers = ["X-PP-User": "user", "X-PP-Groups": "group"]

        def messageChain1
        def messageChain2
        when: "the user sends their request"
        5.times {
            messageChain1 = deproxy.makeRequest(url: reposeOneEndpoint, headers: headers)
            messageChain2 = deproxy.makeRequest(url: reposeTwoEndpoint, headers: headers)

            then: "the request is not rate-limited, and passes to the origin service"
            assert messageChain1.receivedResponse.code as Integer == SC_OK
            assert messageChain1.handlings.size() == 1
            assert messageChain2.receivedResponse.code as Integer == SC_OK
            assert messageChain2.handlings.size() == 1
        }

        and: "the user sends their request after the rate-limit has been reached"
        messageChain1 = deproxy.makeRequest(url: reposeOneEndpoint, headers: headers)
        messageChain2 = deproxy.makeRequest(url: reposeTwoEndpoint, headers: headers)

        then: "the request is rate-limited, and passes to the origin service"
        messageChain1.receivedResponse.code as Integer == SC_REQUEST_ENTITY_TOO_LARGE
        messageChain1.handlings.size() == 0
        messageChain2.receivedResponse.code as Integer == SC_REQUEST_ENTITY_TOO_LARGE
        messageChain2.handlings.size() == 0
    }
}
