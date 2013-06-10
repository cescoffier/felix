package org.apache.felix.ipojo.runtime.core.test.interceptors;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.ServiceProperty;
import org.apache.felix.ipojo.dependency.interceptors.EmptyServiceTrackingInterceptor;
import org.apache.felix.ipojo.dependency.interceptors.TransformedServiceReference;
import org.apache.felix.ipojo.util.DependencyModel;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.util.ArrayList;
import java.util.List;

/**
 * An interceptor adding a property (location) and hiding another property (hidden)
 * Not instantiated by default.
 */
@Component(immediate = true)
@Provides
public class AddLocationInterceptor extends EmptyServiceTrackingInterceptor {

    List<DependencyModel> dependencies = new ArrayList<DependencyModel>();

    @ServiceProperty
    private String target;


    @Override
    public void open(DependencyModel dependency, BundleContext context) {
        System.out.println("open called for " + dependency.getId());
        dependencies.add(dependency);
    }

    @Override
    public <S> TransformedServiceReference<S> accept(DependencyModel dependency, BundleContext context,
                                          TransformedServiceReference<S> ref) {
        System.out.println("Accept called");
        return ref
                .addProperty("location", "kitchen") // Because Brian is in the kitchen.
                .removeProperty("hidden");
    }

    @Override
    public void close(DependencyModel dependency, BundleContext context) {
        dependencies.remove(dependency);
    }
}
