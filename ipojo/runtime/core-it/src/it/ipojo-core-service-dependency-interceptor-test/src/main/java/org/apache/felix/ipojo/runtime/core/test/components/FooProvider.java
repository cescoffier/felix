package org.apache.felix.ipojo.runtime.core.test.components;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.StaticServiceProperty;
import org.apache.felix.ipojo.runtime.core.test.services.FooService;

import java.util.Properties;

/**
 * Provides FooService
 */
@Component
@Provides(properties = @StaticServiceProperty(name="hidden", value = "hidden", type ="string"))
public class FooProvider implements FooService {
    @Override
    public boolean foo() {
        return true;
    }

    @Override
    public Properties fooProps() {
        return null;
    }
}
