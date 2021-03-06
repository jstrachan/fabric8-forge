/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.forge.camel;

import javax.inject.Inject;

import org.apache.camel.Endpoint;
import org.apache.camel.EndpointInject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.cdi.Uri;

public class MyCdiConcatRouteBuilder extends RouteBuilder {

    private static final int DELAY = 4999;
    private static final int PORT = 80;

    @Inject
    @Uri("timer:foo?period=" + DELAY)
    private Endpoint inputEndpoint;

    @Inject
    @Uri("log:a")
    private Endpoint loga;

    @EndpointInject(uri = "netty4-http:http:someserver:" + PORT + "/hello")
    private Endpoint mynetty;

    @Override
    public void configure() throws Exception {
        from(inputEndpoint)
            .log("I was here")
            .to(loga)
            .to(mynetty);
    }
}
