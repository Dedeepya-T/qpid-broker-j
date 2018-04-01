/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.util;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import org.apache.qpid.test.utils.UnitTestBase;

public class StringsTest extends UnitTestBase
{
    @Test
    public void testSubstitutionResolver()
    {
        Strings.MapResolver mapResolver =
                new Strings.MapResolver(Collections.singletonMap("test", "C:\\TEMP\\\"Hello World\""));

        Strings.Resolver jsonResolver = Strings.createSubstitutionResolver("json:",
                                                                           new LinkedHashMap<String, String>()
                                                                           {
                                                                               {
                                                                                   put("\\", "\\\\");
                                                                                   put("\"", "\\\"");
                                                                               }
                                                                           });

        assertEquals("{ \"path\" : \"C:\\\\TEMP\\\\\\\"Hello World\\\"\\foo\" }",
                            Strings.expand("{ \"path\" : \"${json:test}\\foo\" }", Strings.chain(jsonResolver, mapResolver)));
    }

    @Test
    public void testNestedSubstitutionResolver()
    {
        Map<String,String> context = new HashMap<>();
        context.put("test", "C:\\TEMP\\\"Hello World\"");
        context.put("nestedTest", "${test}");
        Strings.MapResolver mapResolver =
                new Strings.MapResolver(context);

        Strings.Resolver jsonResolver = Strings.createSubstitutionResolver("json:",
                                                                           new LinkedHashMap<String, String>()
                                                                           {
                                                                               {
                                                                                   put("\\", "\\\\");
                                                                                   put("\"", "\\\"");
                                                                               }
                                                                           });

        assertEquals("{ \"path\" : \"C:\\\\TEMP\\\\\\\"Hello World\\\"\\foo\" }",
                            Strings.expand("{ \"path\" : \"${json:nestedTest}\\foo\" }", Strings.chain(jsonResolver, mapResolver)));
    }
}
