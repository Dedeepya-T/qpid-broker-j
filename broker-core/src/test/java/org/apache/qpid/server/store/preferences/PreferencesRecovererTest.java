/*
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
 */

package org.apache.qpid.server.store.preferences;

import static org.mockito.Mockito.mock;

import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.security.auth.Subject;

import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Model;
import org.apache.qpid.server.model.preferences.Preference;
import org.apache.qpid.server.model.preferences.PreferenceTestHelper;
import org.apache.qpid.server.model.testmodels.hierarchy.TestCar;
import org.apache.qpid.server.model.testmodels.hierarchy.TestEngine;
import org.apache.qpid.server.model.testmodels.hierarchy.TestModel;
import org.apache.qpid.server.security.auth.TestPrincipalUtils;
import org.apache.qpid.test.utils.QpidTestCase;

public class PreferencesRecovererTest extends QpidTestCase
{
    public static final String TEST_USERNAME = "testUser";
    private final Model _model = TestModel.getInstance();
    private PreferenceStore _store;
    private TestCar _testObject;
    private ConfiguredObject<?> _testChildObject;
    private Subject _testSubject;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _store = mock(PreferenceStore.class);
        _testObject = _model.getObjectFactory()
                            .create(TestCar.class,
                                    Collections.<String, Object>singletonMap(ConfiguredObject.NAME, getTestName()));
        _testChildObject = _testObject.createChild(TestEngine.class,
                                                   Collections.<String, Object>singletonMap(ConfiguredObject.NAME, getTestName()));
        _testSubject = TestPrincipalUtils.createTestSubject(TEST_USERNAME);
    }

    public void testRecoverEmptyPreferences() throws Exception
    {
        PreferencesRecoverer recoverer = new PreferencesRecoverer();
        recoverer.recoverPreferences(_testObject, Collections.<PreferenceRecord>emptyList(), _store);
        assertNotNull("Object should have UserPreferences", _testObject.getUserPreferences());
        assertNotNull("Child object should have UserPreferences", _testChildObject.getUserPreferences());
    }

    public void testRecoverPreferences() throws Exception
    {
        PreferencesRecoverer recoverer = new PreferencesRecoverer();

        final UUID p1Id = UUID.randomUUID();
        Map<String, Object> pref1Attributes = PreferenceTestHelper.createPreferenceAttributes(
                _testObject.getId(),
                p1Id,
                "X-testType",
                "testPref1",
                null,
                TEST_USERNAME,
                null,
                Collections.<String, Object>emptyMap());
        PreferenceRecord record1 = new PreferenceRecordImpl(p1Id, pref1Attributes);
        final UUID p2Id = UUID.randomUUID();
        Map<String, Object> pref2Attributes = PreferenceTestHelper.createPreferenceAttributes(
                _testChildObject.getId(),
                p2Id,
                "X-testType",
                "testPref2",
                null,
                TEST_USERNAME,
                null,
                Collections.<String, Object>emptyMap());
        PreferenceRecord record2 = new PreferenceRecordImpl(p2Id, pref2Attributes);
        recoverer.recoverPreferences(_testObject, Arrays.asList(record1, record2), _store);

        Subject.doAs(_testSubject, new PrivilegedAction<Void>()
        {
            @Override
            public Void run()
            {
                Set<Preference> preferences = _testObject.getUserPreferences().getPreferences();
                assertEquals("Unexpected number of preferences", 1, preferences.size());

                Set<Preference> childPreferences = _testChildObject.getUserPreferences().getPreferences();
                assertEquals("Unexpected number of preferences", 1, childPreferences.size());
                return null;
            }
        });
    }

}