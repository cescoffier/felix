/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 */

package org.apache.felix.ipojo.dependency.impl;

import org.apache.felix.ipojo.Factory;
import org.apache.felix.ipojo.util.DependencyModel;
import org.osgi.framework.Bundle;

import java.util.Dictionary;
import java.util.Hashtable;

/**
 * Builds the properties used to checks if an interceptor matches a specific dependency.
 */
public class DependencyProperties {

    //TODO Externalize and use constants

    public static  Dictionary<String, ?> getDependencyProperties(DependencyModel dependency) {
        Dictionary<String, Object> properties = new Hashtable<String, Object>();

        // Instance, and Factory and Bundle (name, symbolic name, version)
        properties.put(Factory.INSTANCE_NAME_PROPERTY, dependency.getComponentInstance().getInstanceName());
        properties.put("instance.state", dependency.getComponentInstance().getState());
        properties.put("factory.name", dependency.getComponentInstance().getFactory().getFactoryName());
        final Bundle bundle = dependency.getComponentInstance().getFactory().getBundleContext().getBundle();
        properties.put("bundle.symbolicName", bundle.getSymbolicName());
        if (bundle.getVersion() != null) {
            properties.put("bundle.version", bundle.getVersion().toString());
        }

        // Dependency specification, and id
        properties.put("dependency.specification", dependency.getSpecification().getName());
        properties.put("dependency.id", dependency.getId());
        properties.put("dependency.state", dependency.getState());

        return properties;
    }
}
