package org.apache.felix.ipojo.runtime.core.test.components;

import org.apache.felix.ipojo.annotations.Bind;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.runtime.core.test.services.CheckService;
import org.apache.felix.ipojo.runtime.core.test.services.FooService;

import java.util.Map;
import java.util.Properties;

/**
 * A component consuming FooService
 */
@Component(immediate = true)
@Provides
public class FooConsumer implements CheckService {

    @Requires(id= "foo")
    private FooService foo;

    private Map<String, ?> props;

    @Override
    public boolean check() {
        return foo != null;
    }

    @Override
    public Properties getProps() {
        Properties properties =  new Properties();
        properties.put("props", props);
        return properties;
    }

    @Bind(id="foo")
    public void bind(FooService foo, Map<String, ?> properties) {
        props = properties;
    }
}
