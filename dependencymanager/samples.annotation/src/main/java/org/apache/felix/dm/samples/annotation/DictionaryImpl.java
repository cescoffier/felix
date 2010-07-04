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
package org.apache.felix.dm.samples.annotation;

import java.util.Dictionary;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.felix.dm.annotation.api.PropertyMetaData;
import org.apache.felix.dm.annotation.api.FactoryConfigurationAdapterService;
import org.apache.felix.dm.annotation.api.ServiceDependency;
import org.apache.felix.dm.annotation.api.Start;
import org.osgi.service.log.LogService;

/**
 * A Dictionary Service. This service uses a FactoryConfigurationAdapterService annotation, 
 * allowing to instantiate this service from webconsole. This annotation will actually register
 * a ManagedServiceFactory in the registry, and also supports meta types for configuring this
 * service from WebConsole.
 * 
 * You must configure at least one Dictionary from web console, since the SpellCheck won't start if no Dictionary
 * Service is available.
 */
@FactoryConfigurationAdapterService(
    factoryPid="DictionaryServiceFactory", 
    propagate=true, 
    updated="updated",
    heading="Dictionary Services",
    description="Declare here some Dictionary instances, allowing to instantiates some DictionaryService services for a given dictionary language",
    metadata={
        @PropertyMetaData(
            heading="Dictionary Language",
            description="Declare here the language supported by this dictionary. " +
                "This property will be propagated with the Dictionary Service properties.",
            defaults={"en"},
            id="lang",
            cardinality=0),
        @PropertyMetaData(
            heading="Dictionary words",
            description="Declare here the list of words supported by this dictionary.",
            defaults={"hello", "world"},
            id="words",
            cardinality=Integer.MAX_VALUE)
    }
)  
public class DictionaryImpl implements DictionaryService
{
    /**
     * We store all configured words in a thread-safe data structure, because ConfigAdmin
     * may invoke our updated method at any time.
     */
    private CopyOnWriteArrayList<String> m_words = new CopyOnWriteArrayList<String>();
    
    /**
     * We'll use the OSGi log service for logging. If no log service is available, then we'll use a NullObject.
     */
    @ServiceDependency(required = false)
    private LogService m_log;

    /**
     * Our Dictionary language.
     */
    private String m_lang;

    /**
     * Our service will be initialized from ConfigAdmin.
     * @param config The configuration where we'll lookup our words list (key="words").
     */
    protected void updated(Dictionary<String, ?> config) {
        m_lang = (String) config.get("lang");
        m_words.clear();
        String[] words = (String[]) config.get("words");
        for (String word : words) {
            m_words.add(word);
        }
    }

    /**
     * A new Dictionary Service is starting (because a new factory configuration has been created
     * from webconsole).
     */
    @Start
    protected void start() 
    {
        m_log.log(LogService.LOG_INFO, "Starting Dictionary Service with language: " + m_lang);
    }
           
    /**
     * Check if a word exists if the list of words we have been configured from ConfigAdmin/WebConsole.
     */
    public boolean checkWord(String word)
    {
        return m_words.contains(word);
    }
    
    @Override
    public String toString() 
    {
        return "Dictionary: language=" + m_lang + ", words=" + m_words;
    }
}
