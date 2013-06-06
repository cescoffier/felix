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
package org.apache.felix.ipojo.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
* This annotation declares that the component instances will provide a service.
* @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
*/
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface Provides {

    /**
     * Set the provided specifications.
     * Default : all implemented interfaces
     */
    Class[] specifications() default { };

    /**
     * Set the service object creation strategy.
     * Two value are possible: SINGLETON, SERVICE, METHOD, INSTANCE or the strategy class name.
     * SERVICE means OSGi Service Factory.
     * METHOD delegates the creation to the factory-method of the component
     * INSTANCE creates one service object per requiring instance
     * for other strategies, specify the qualified name of the CreationStrategy class.
     * Default : SINGLETON
     */
    String strategy() default "SINGLETON";

    /**
     * Allows adding static properties to the service.
     * Nested properties are static service properties,
     * so <b>must</b> contain the name and the value as
     * they are not attached to a field.
     * The array contains {@link StaticServiceProperty} elements.
     * Default : No service properties
     */
    StaticServiceProperty[] properties() default {};
}
